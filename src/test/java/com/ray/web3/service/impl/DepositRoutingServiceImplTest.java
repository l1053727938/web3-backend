package com.ray.web3.service.impl;

import com.ray.web3.config.DepositProperties;
import com.ray.web3.config.InfuraProperties;
import com.ray.web3.mapper.DepositRecordMapper;
import com.ray.web3.model.DepositConsts;
import com.ray.web3.model.dto.TransferEventDTO;
import com.ray.web3.model.entity.DepositRecord;
import com.ray.web3.model.entity.DepositWallet;
import com.ray.web3.service.DepositWalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

import java.math.BigInteger;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 模块四充值路由离线单测（REQ-BIZ-04）：热钱包匹配 / txHash+logIndex 幂等 / 待确认落库 / deposit_channel 发布
 * <p>
 * 全部依赖 Mock，不连 MySQL 与 Redis
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
    private StringRedisTemplate stringRedisTemplate;

    private DepositProperties depositProperties;
    private DepositRoutingServiceImpl service;

    @BeforeEach
    void setUp() {
        depositProperties = new DepositProperties();
        InfuraProperties infuraProperties = new InfuraProperties();
        infuraProperties.setChainId(CHAIN_ID);
        service = new DepositRoutingServiceImpl(depositWalletService, depositRecordMapper,
                stringRedisTemplate, new ObjectMapper(), infuraProperties, depositProperties);
    }

    @Test
    void 命中热钱包应写入待确认充值记录并发布deposit_channel() {
        givenHotWalletMatched();
        when(depositRecordMapper.insertIgnoreBatch(any())).thenReturn(1);
        when(depositRecordMapper.selectByTxLog(CHAIN_ID, TX_HASH, 7L)).thenReturn(storedRecord());

        long inserted = service.route(List.of(event()), DepositConsts.Source.STREAM);

        assertThat(inserted).isEqualTo(1);
        ArgumentCaptor<List<DepositRecord>> captor = ArgumentCaptor.forClass(List.class);
        verify(depositRecordMapper).insertIgnoreBatch(captor.capture());
        DepositRecord record = captor.getValue().getFirst();
        // 验收标准：目标地址属于平台热钱包 → 新增一条状态为"待确认"的充值记录
        assertThat(record.getStatus()).isEqualTo(DepositConsts.Status.PENDING_CONFIRM);
        assertThat(record.getToAddress()).isEqualTo(HOT_WALLET);
        assertThat(record.getWalletType()).isEqualTo(DepositConsts.WalletType.HOT);
        assertThat(record.getTransactionHash()).isEqualTo(TX_HASH);
        assertThat(record.getLogIndex()).isEqualTo(7L);
        assertThat(record.getNotifyStatus()).isEqualTo(DepositConsts.Notify.INIT);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(stringRedisTemplate).convertAndSend(eq("deposit_channel"), payload.capture());
        // 消息体带的是回读后的入库行（含自增 id 与单号），下游可据此幂等
        assertThat(payload.getValue())
                .contains("\"depositNo\":\"DP17000000000000001\"")
                .contains("\"status\":\"PENDING_CONFIRM\"")
                .contains("\"toAddress\":\"" + HOT_WALLET + "\"")
                .contains("\"amount\":\"100.000000\"");

        verify(depositRecordMapper).updateById(argIdAndStatus(1L, DepositConsts.Notify.SENT));
    }

    @Test
    void 重复事件幂等且不再发布下游消息() {
        givenHotWalletMatched();
        // INSERT IGNORE 命中唯一键：0 行新增，回读也拿不到本次写入（重放/分页重叠场景）
        when(depositRecordMapper.insertIgnoreBatch(any())).thenReturn(0);
        when(depositRecordMapper.selectByTxLog(CHAIN_ID, TX_HASH, 7L)).thenReturn(null);

        long inserted = service.route(List.of(event()), DepositConsts.Source.BACKFILL);

        assertThat(inserted).isZero();
        verify(stringRedisTemplate, never()).convertAndSend(anyString(), anyString());
        verify(depositRecordMapper, never()).updateById(any(DepositRecord.class));
    }

    @Test
    void 非平台地址不产生充值记录() {
        when(depositWalletService.match(anyString())).thenReturn(null);

        long inserted = service.route(List.of(event()), DepositConsts.Source.STREAM);

        assertThat(inserted).isZero();
        verify(depositRecordMapper, never()).insertIgnoreBatch(any());
        verify(stringRedisTemplate, never()).convertAndSend(anyString(), anyString());
    }

    @Test
    void 配置限定合约与事件合约不一致时不计充值() {
        DepositWallet wallet = hotWallet();
        wallet.setContract("0x0000000000000000000000000000000000000001");
        when(depositWalletService.match(HOT_WALLET)).thenReturn(wallet);

        assertThat(service.route(List.of(event()), DepositConsts.Source.STREAM)).isZero();
        verify(depositRecordMapper, never()).insertIgnoreBatch(any());
    }

    @Test
    void 发布失败时标记FAILED但充值记录保持已入库() {
        givenHotWalletMatched();
        when(depositRecordMapper.insertIgnoreBatch(any())).thenReturn(1);
        when(depositRecordMapper.selectByTxLog(CHAIN_ID, TX_HASH, 7L)).thenReturn(storedRecord());
        doThrow(new IllegalStateException("redis down"))
                .when(stringRedisTemplate).convertAndSend(anyString(), any());

        assertThat(service.route(List.of(event()), DepositConsts.Source.STREAM)).isEqualTo(1);

        verify(depositRecordMapper).updateById(argIdAndStatus(1L, DepositConsts.Notify.FAILED));
    }

    @Test
    void 关闭开关后不做任何路由() {
        depositProperties.setEnabled(false);

        assertThat(service.route(List.of(event()), DepositConsts.Source.STREAM)).isZero();
        verify(depositWalletService, never()).match(anyString());
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
        record.setNotifyStatus(DepositConsts.Notify.SENT);
        return record;
    }

    /** 只关心 id + notifyStatus 的匹配器（partial update 只更新通知两列） */
    private static DepositRecord argIdAndStatus(Long id, String notifyStatus) {
        return org.mockito.ArgumentMatchers.argThat(r ->
                r != null && id.equals(r.getId()) && notifyStatus.equals(r.getNotifyStatus()));
    }
}
