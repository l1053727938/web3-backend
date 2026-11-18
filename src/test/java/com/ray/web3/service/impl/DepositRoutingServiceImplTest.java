package com.ray.web3.service.impl;

import com.ray.web3.config.DepositProperties;
import com.ray.web3.config.InfuraProperties;
import com.ray.web3.consts.DepositConsts;
import com.ray.web3.consts.OutboxConsts;
import com.ray.web3.mapper.DepositRecordMapper;
import com.ray.web3.mapper.OutboxMessageMapper;
import com.ray.web3.model.dto.TransferEventDTO;
import com.ray.web3.model.entity.DepositRecord;
import com.ray.web3.model.entity.DepositWallet;
import com.ray.web3.model.entity.OutboxMessage;
import com.ray.web3.service.DepositWalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 模块四充值路由 + Outbox 同事务改造离线单测（第 3 周 Day 3-4）
 * <p>
 * 重点锁定：
 * <ul>
 *   <li>充值记录与 outbox_message 在同一条代码路径里写入，<b>不再直接发布 Redis</b>；</li>
 *   <li>业务写入抛异常时不会继续写 outbox（真实事务回滚由 {@code @Transactional} 保证）；</li>
 *   <li>outbox 写入抛异常时异常向上抛出，整个事务回滚，不会出现“充值记录落了、消息没落”；</li>
 *   <li>{@code route()} 必须声明 {@code rollbackFor = Exception.class}，否则受检异常不会回滚。</li>
 * </ul>
 * 全部依赖 Mock，不连 MySQL/Redis；真实事务边界由 Spring 代理在运行时生效。
 */
@ExtendWith(MockitoExtension.class)
class DepositRoutingServiceImplTest {

    private static final long CHAIN_ID = 1L;
    private static final String HOT_WALLET = "0x0fd04a68d3c3a692d6fa30384d1a87ef93554ee6";
    private static final String USDT = "0xdac17f958d2ee523a2206206994597c13d831ec7";
    private static final String TX_HASH = "0xaaa1";

    @Mock
    private DepositWalletService depositWalletService;
    @Mock
    private DepositRecordMapper depositRecordMapper;
    @Mock
    private OutboxMessageMapper outboxMessageMapper;

    private DepositProperties depositProperties;
    private DepositRoutingServiceImpl service;

    @BeforeEach
    void setUp() {
        depositProperties = new DepositProperties();
        InfuraProperties infuraProperties = new InfuraProperties();
        infuraProperties.setChainId(CHAIN_ID);
        service = new DepositRoutingServiceImpl(depositWalletService, depositRecordMapper,
                outboxMessageMapper, new ObjectMapper(), infuraProperties, depositProperties);
    }

    @Test
    void 命中热钱包应写入待确认充值记录并同事务写outbox消息() {
        givenHotWalletMatched();
        when(depositRecordMapper.insertIgnoreBatch(any())).thenReturn(1);
        when(depositRecordMapper.selectByTxLog(CHAIN_ID, TX_HASH, 7L)).thenReturn(storedRecord());

        long inserted = service.route(List.of(event()), DepositConsts.Source.STREAM);

        assertThat(inserted).isEqualTo(1);

        // 1) 充值记录仍是待确认，notify_status=INIT 等投递器回填
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DepositRecord>> recordCaptor = ArgumentCaptor.forClass(List.class);
        verify(depositRecordMapper).insertIgnoreBatch(recordCaptor.capture());
        DepositRecord record = recordCaptor.getValue().getFirst();
        assertThat(record.getStatus()).isEqualTo(DepositConsts.Status.PENDING_CONFIRM);
        assertThat(record.getToAddress()).isEqualTo(HOT_WALLET);
        assertThat(record.getWalletType()).isEqualTo(DepositConsts.WalletType.HOT);
        assertThat(record.getTransactionHash()).isEqualTo(TX_HASH);
        assertThat(record.getLogIndex()).isEqualTo(7L);
        assertThat(record.getNotifyStatus()).isEqualTo(DepositConsts.Notify.INIT);

        // 2) 同事务写一条 NEW 的 Outbox 消息，payload 是入库行快照
        ArgumentCaptor<OutboxMessage> outboxCaptor = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(outboxMessageMapper).insert(outboxCaptor.capture());
        OutboxMessage message = outboxCaptor.getValue();
        assertThat(message.getBizType()).isEqualTo(OutboxConsts.BizType.DEPOSIT);
        assertThat(message.getBizNo()).isEqualTo("DP17000000000000001");
        assertThat(message.getStatus()).isEqualTo(OutboxConsts.Status.NEW);
        assertThat(message.getRetryCount()).isZero();
        // HOT 钱包无归属用户：分区键回退充值单号
        assertThat(message.getPartitionKey()).isEqualTo(message.getBizNo());
        assertThat(message.getPayload())
                .contains("\"depositNo\":\"DP17000000000000001\"")
                .contains("\"status\":\"PENDING_CONFIRM\"")
                .contains("\"toAddress\":\"" + HOT_WALLET + "\"")
                .contains("\"amount\":\"100.000000\"");

        // 3) 不再直接发布，也不在路由阶段回填 notify_status（由投递器负责）
        verify(depositRecordMapper, never()).updateById(any(DepositRecord.class));
    }

    @Test
    void 写outbox前应按收款地址反查用户并回填() {
        givenHotWalletMatched();
        when(depositRecordMapper.insertIgnoreBatch(any())).thenReturn(1);
        DepositRecord stored = storedRecord();
        stored.setUserId(null);
        when(depositRecordMapper.selectByTxLog(CHAIN_ID, TX_HASH, 7L)).thenReturn(stored);
        when(depositWalletService.findUserIdsByAddresses(CHAIN_ID, List.of(HOT_WALLET)))
                .thenReturn(Map.of(HOT_WALLET, "10001"));

        assertThat(service.route(List.of(event()), DepositConsts.Source.STREAM)).isEqualTo(1);

        // 业务表回填 userId/walletType，与 Outbox 载荷保持同一口径
        ArgumentCaptor<DepositRecord> updateCaptor = ArgumentCaptor.forClass(DepositRecord.class);
        verify(depositRecordMapper).updateById(updateCaptor.capture());
        assertThat(updateCaptor.getValue().getUserId()).isEqualTo("10001");
        assertThat(updateCaptor.getValue().getWalletType()).isEqualTo(DepositConsts.WalletType.USER);

        ArgumentCaptor<OutboxMessage> outboxCaptor = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(outboxMessageMapper).insert(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().getPartitionKey())
                .as("USER 充值的 Kafka key 必须是 userId，保证同一用户进同一分区")
                .isEqualTo("10001");
        assertThat(outboxCaptor.getValue().getPayload())
                .contains("\"userId\":\"10001\"")
                .contains("\"walletType\":\"USER\"");
    }

    @Test
    void 重复事件幂等且不写outbox消息() {
        givenHotWalletMatched();
        // 整片 INSERT IGNORE 0 行新增：首次入账事务已把 outbox 消息同事务写好，本次不重复补
        when(depositRecordMapper.insertIgnoreBatch(any())).thenReturn(0);

        long inserted = service.route(List.of(event()), DepositConsts.Source.BACKFILL);

        assertThat(inserted).isZero();
        verify(depositRecordMapper, never()).selectByTxLog(any(), any(), any());
        verify(outboxMessageMapper, never()).insert(any(OutboxMessage.class));
    }

    @Test
    void 唯一键冲突回读已存在行时仍补写outbox消息() {
        givenHotWalletMatched();
        when(depositRecordMapper.insertIgnoreBatch(any()))
                .thenThrow(new DataIntegrityViolationException("uk duplicated"));
        when(depositRecordMapper.selectByTxLog(CHAIN_ID, TX_HASH, 7L)).thenReturn(storedRecord());

        long inserted = service.route(List.of(event()), DepositConsts.Source.STREAM);

        // 回读命中视为已入库；outbox 仍补一条，至少一次投递，消费端按 bizNo 幂等
        assertThat(inserted).isEqualTo(1);
        verify(outboxMessageMapper).insert(any(OutboxMessage.class));
    }

    @Test
    void 非平台地址不产生充值记录和outbox消息() {
        when(depositWalletService.match(anyString())).thenReturn(null);

        long inserted = service.route(List.of(event()), DepositConsts.Source.STREAM);

        assertThat(inserted).isZero();
        verify(depositRecordMapper, never()).insertIgnoreBatch(any());
        verify(outboxMessageMapper, never()).insert(any(OutboxMessage.class));
    }

    @Test
    void 配置限定合约与事件合约不一致时不计充值() {
        DepositWallet wallet = hotWallet();
        wallet.setContract("0x0000000000000000000000000000000000000001");
        when(depositWalletService.match(HOT_WALLET)).thenReturn(wallet);

        assertThat(service.route(List.of(event()), DepositConsts.Source.STREAM)).isZero();
        verify(depositRecordMapper, never()).insertIgnoreBatch(any());
        verify(outboxMessageMapper, never()).insert(any(OutboxMessage.class));
    }

    @Test
    void 业务写入异常时不写outbox且异常向上抛出() {
        givenHotWalletMatched();
        when(depositRecordMapper.insertIgnoreBatch(any()))
                .thenThrow(new IllegalStateException("business db down"));

        assertThatThrownBy(() -> service.route(List.of(event()), DepositConsts.Source.STREAM))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("business db down");

        // 业务写入失败时不会走到 outbox；真实事务中 deposit_record 也会因异常回滚
        verify(outboxMessageMapper, never()).insert(any(OutboxMessage.class));
    }

    @Test
    void outbox写入异常向上抛出以触发整个事务回滚() {
        givenHotWalletMatched();
        when(depositRecordMapper.insertIgnoreBatch(any())).thenReturn(1);
        when(depositRecordMapper.selectByTxLog(CHAIN_ID, TX_HASH, 7L)).thenReturn(storedRecord());
        when(outboxMessageMapper.insert(any(OutboxMessage.class)))
                .thenThrow(new IllegalStateException("outbox db down"));

        assertThatThrownBy(() -> service.route(List.of(event()), DepositConsts.Source.STREAM))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outbox db down");
    }

    @Test
    void route方法必须声明回滚所有异常的事务() throws Exception {
        Transactional tx = DepositRoutingServiceImpl.class
                .getMethod("route", List.class, String.class)
                .getAnnotation(Transactional.class);

        assertThat(tx).as("充值记录与 outbox 必须同事务，且任何异常都要回滚").isNotNull();
        assertThat(tx.rollbackFor()).contains(Exception.class);
    }

    @Test
    void 关闭开关后不做任何路由() {
        depositProperties.setEnabled(false);

        assertThat(service.route(List.of(event()), DepositConsts.Source.STREAM)).isZero();
        verifyNoInteractions(depositWalletService, depositRecordMapper, outboxMessageMapper);
    }

    private void givenHotWalletMatched() {
        when(depositWalletService.match(HOT_WALLET)).thenReturn(hotWallet());
    }

    private DepositWallet hotWallet() {
        DepositWallet wallet = new DepositWallet();
        wallet.setChainId(CHAIN_ID);
        wallet.setAddress(HOT_WALLET);
        wallet.setWalletType(DepositConsts.WalletType.HOT);
        wallet.setCurrency("USDT");
        wallet.setStatus(1);
        return wallet;
    }

    private TransferEventDTO event() {
        return new TransferEventDTO(USDT, BigInteger.valueOf(20_000_000L), TX_HASH, BigInteger.valueOf(7L),
                "0x95222290dd7278aa3ddd389b1e2d9d2f0f4b0001", HOT_WALLET,
                new BigInteger("100000000"), "100.000000", 6);
    }

    /** 模拟落库后回读到的行（带自增 id 与入库时间） */
    private DepositRecord storedRecord() {
        DepositRecord record = new DepositRecord();
        record.setId(1L);
        record.setDepositNo("DP17000000000000001");
        record.setChainId(CHAIN_ID);
        record.setContractAddress(USDT);
        record.setTransactionHash(TX_HASH);
        record.setLogIndex(7L);
        record.setBlockNumber(20_000_000L);
        record.setFromAddress("0x95222290dd7278aa3ddd389b1e2d9d2f0f4b0001");
        record.setToAddress(HOT_WALLET);
        record.setWalletType(DepositConsts.WalletType.HOT);
        record.setCurrency("USDT");
        record.setRawValue("100000000");
        record.setDisplayValue("100.000000");
        record.setDecimals(6);
        record.setStatus(DepositConsts.Status.PENDING_CONFIRM);
        record.setEventSource(DepositConsts.Source.STREAM);
        record.setNotifyStatus(DepositConsts.Notify.INIT);
        record.setCreatedAt(java.time.LocalDateTime.of(2026, 11, 16, 10, 0));
        return record;
    }
}