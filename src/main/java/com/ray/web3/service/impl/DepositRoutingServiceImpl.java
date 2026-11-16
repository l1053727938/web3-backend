package com.ray.web3.service.impl;

import com.ray.web3.config.DepositProperties;
import com.ray.web3.config.InfuraProperties;
import com.ray.web3.consts.DbConsts;
import com.ray.web3.consts.DepositConsts;
import com.ray.web3.consts.OutboxConsts;
import com.ray.web3.mapper.DepositRecordMapper;
import com.ray.web3.mapper.OutboxMessageMapper;
import com.ray.web3.model.dto.TransferEventDTO;
import com.ray.web3.model.entity.DepositRecord;
import com.ray.web3.model.entity.DepositWallet;
import com.ray.web3.model.entity.OutboxMessage;
import com.ray.web3.model.vo.DepositEventVO;
import com.ray.web3.service.DepositRoutingService;
import com.ray.web3.service.DepositWalletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 充值路由实现（REQ-BIZ-04 模块四 + 第 3 周 Day 3-4 Outbox 改造）
 * <p>
 * 热钱包匹配 → 幂等落库（待确认）→ <b>同事务写 outbox_message</b>。
 * <p>
 * 关键设计：
 * <ol>
 *   <li><b>同事务</b>：{@code deposit_record} 与 {@code outbox_message} 在同一个
 *       {@code @Transactional} 里写入，要么都成功、要么都回滚。这是 Outbox 的核心：
 *       不存在“业务成了消息丢了”的窗口，也不存在“消息发了业务回滚”的悬挂通知。</li>
 *   <li><b>幂等口径</b>：INSERT IGNORE 命中唯一键 (chain_id, txHash, log_index) 返回 0 行即视为重复；
 *       重复事件不会新增充值行，但并发冲突回读到的已存在行仍会补一条 Outbox 消息——
 *       投递语义是“至少一次”，重复由消费端 consumer_biz 幂等表挡住，宁重不漏。</li>
 *   <li><b>投递解耦</b>：本方法不再直接 PUBLISH Redis，只负责把消息落到 outbox_message；
 *       独立投递器 {@code OutboxDispatcher} 扫表发送，失败退避重试。投递器停掉时业务照常落库。</li>
 *   <li><b>notify_status</b>：落库时固定 INIT，由投递器在发送成功/失败后回填 SENT/FAILED，
 *       保留“充值业务侧 + 消息投递侧”双层可观测。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "web3.deposit.enabled", havingValue = "true", matchIfMissing = true)
public class DepositRoutingServiceImpl implements DepositRoutingService {

    private static final String DEPOSIT_NO_PREFIX = "DP";

    /** 单号中的可读时间戳：月日时分秒毫秒（不含年，控制总长度≤VARCHAR(64)） */
    private static final DateTimeFormatter DEPOSIT_NO_TIME = DateTimeFormatter.ofPattern("MMddHHmmssSSS");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final DepositWalletService depositWalletService;
    private final DepositRecordMapper depositRecordMapper;
    private final OutboxMessageMapper outboxMessageMapper;
    private final ObjectMapper objectMapper;
    private final InfuraProperties infuraProperties;
    private final DepositProperties depositProperties;

    /**
     * 路由一批 Transfer 事件。
     * <p>
     * 整个方法是一个本地事务：任何一条充值记录写入失败、或 Outbox 消息写入/序列化失败，
     * 本批已写的充值记录和消息全部回滚，调用方看到异常，不会留下“半批入账”。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public long route(List<TransferEventDTO> events, String source) {
        if (events == null || events.isEmpty() || !depositProperties.isEnabled()) {
            return 0;
        }
        // 按入库批大小切片：上游工作线程可能一次攒上千条，分片路由让单批拼包与内存占用可控
        int batchSize = DbConsts.INSERT_BATCH_SIZE;
        long total = 0;
        for (int from = 0; from < events.size(); from += batchSize) {
            total += routeChunk(events.subList(from, Math.min(from + batchSize, events.size())), source);
        }
        return total;
    }

    /**
     * 单片路由：匹配 → 幂等落库 → 同事务写 Outbox，返回本片新增充值记录数
     */
    private long routeChunk(List<TransferEventDTO> events, String source) {
        Long chainId = infuraProperties.getChainId();

        // 1. 热钱包/用户地址匹配（内存索引，不逐条打 DB）
        List<DepositRecord> matched = new ArrayList<>();
        for (TransferEventDTO event : events) {
            DepositWallet wallet = depositWalletService.match(event.to());
            if (wallet == null || !contractAccepted(wallet, event.contract())) {
                continue;
            }
            matched.add(toRecord(chainId, event, wallet, source));
        }
        if (matched.isEmpty()) {
            return 0;
        }

        // 2. 幂等落库 + 取回已提交行（含自增 id）：只有真正入库的行才进入通知环节
        List<DepositRecord> toNotify = new ArrayList<>(matched.size());
        long inserted = insertIgnoreChunks(matched, chainId, toNotify);

        // 3. 同事务写 Outbox：与上面的充值记录同生共死
        int enqueued = enqueueOutbox(toNotify);
        log.info("[deposit] 充值路由 source={} events={} matched={} inserted={} outbox={}",
                source, events.size(), matched.size(), inserted, enqueued);
        return inserted;
    }

    /**
     * 分批幂等写入，并把“本次真正新增”的充值行（含自增 id）收集到 toNotify 供写 Outbox
     * <p>
     * INSERT IGNORE 不回填自增 id、也不告知哪一行被忽略，故新增行统一按幂等键回读一次：
     * 回读既能拿到 id（下游消息需要业务主键），也能过滤掉重放/重叠的重复行。
     * <p>
     * 整片 0 行新增说明全是重放/重叠：首次入账事务已经把 outbox 消息同事务写好，
     * 这里不再补，避免每重放一次就多一条重复通知；唯一键冲突回读到的行仍补消息（宁重不漏）。
     *
     * @return 实际新增行数
     */
    private long insertIgnoreChunks(List<DepositRecord> records, Long chainId, List<DepositRecord> toNotify) {
        long inserted = 0;
        for (int from = 0; from < records.size(); from += DbConsts.INSERT_BATCH_SIZE) {
            List<DepositRecord> chunk = records.subList(from,
                    Math.min(from + DbConsts.INSERT_BATCH_SIZE, records.size()));
            int insertedInChunk;
            try {
                insertedInChunk = depositRecordMapper.insertIgnoreBatch(chunk);
                inserted += insertedInChunk;
            } catch (DataIntegrityViolationException e) {
                // 多实例同时路由到同一笔事件时 INSERT IGNORE 仍可能撞上唯一键：逐行回读，已存在则当重复处理
                inserted += recoverConflictingChunk(chunk, chainId, toNotify);
                continue;
            }
            if (insertedInChunk == 0) {
                continue;
            }
            for (DepositRecord record : chunk) {
                DepositRecord stored = depositRecordMapper.selectByTxLog(
                        chainId, record.getTransactionHash(), record.getLogIndex());
                if (stored == null) {
                    // INSERT IGNORE 返回新增但回读为空：只可能是行已被其他事务删掉/未提交隔离，不能静默吞掉
                    log.warn("[deposit] 写入后回读为空（可能已回滚）tx={} logIndex={}",
                            record.getTransactionHash(), record.getLogIndex());
                    continue;
                }
                toNotify.add(stored);
            }
        }
        return inserted;
    }

    /**
     * 唯一键冲突兜底：逐行回读，已存在且金额/地址一致则当作正常入账收集去写 Outbox（不重复插行），
     * 不一致则告警等人工裁决（同一幂等键出现不同金额属于链上异常，不能自动覆盖）
     *
     * @return 回读命中（视为已入库）的行数
     */
    private int recoverConflictingChunk(List<DepositRecord> chunk, Long chainId, List<DepositRecord> toNotify) {
        int recovered = 0;
        for (DepositRecord record : chunk) {
            DepositRecord exist = depositRecordMapper.selectByTxLog(
                    chainId, record.getTransactionHash(), record.getLogIndex());
            if (exist == null) {
                log.error("[deposit] 充值记录写入冲突但回读为空 tx={} logIndex={}，需人工核查",
                        record.getTransactionHash(), record.getLogIndex());
                continue;
            }
            if (!exist.getRawValue().equals(record.getRawValue())
                    || !exist.getToAddress().equalsIgnoreCase(record.getToAddress())) {
                log.error("[deposit] 同一幂等键金额/收款地址不一致，拒绝覆盖 db={} incoming={} tx={}",
                        exist.getRawValue(), record.getRawValue(), record.getTransactionHash());
                continue;
            }
            recovered++;
            toNotify.add(exist);
        }
        return recovered;
    }

    /**
     * 为本次路由涉及的充值行写 Outbox 消息。
     * <p>
     * 包含“本次新增行”与“并发冲突回读到的已存在行”：至少一次投递允许重复，
     * 消费端按 bizNo 幂等；宁重不漏，避免某些实例只写了充值行而通知永久缺失。
     * 任何一条插入/序列化失败都会抛异常，让整个事务回滚。
     */
    private int enqueueOutbox(List<DepositRecord> records) {
        if (records.isEmpty()) {
            return 0;
        }
        for (DepositRecord record : records) {
            OutboxMessage message = new OutboxMessage();
            message.setBizType(OutboxConsts.BizType.DEPOSIT);
            message.setBizNo(record.getDepositNo());
            message.setPayload(toPayload(record));
            message.setStatus(OutboxConsts.Status.NEW);
            message.setRetryCount(0);
            // next_retry_at 不在这里赋值：由 DB 默认 CURRENT_TIMESTAMP(3) 填充，
            // 与投递器扫描的 NOW(3) 使用同一时钟，避免 JVM/MySQL 时区不一致
            outboxMessageMapper.insert(message);
        }
        return records.size();
    }

    /**
     * 消息体必须在业务事务内生成：投递时再查业务表可能已被后续操作修改，
     * 下游拿到的就不是“入账当时”的快照了
     */
    private String toPayload(DepositRecord record) {
        try {
            return objectMapper.writeValueAsString(toMessage(record));
        } catch (Exception e) {
            // 序列化失败必须让整个事务回滚：充值记录与待投递消息必须同生共死
            throw new IllegalStateException("充值通知序列化失败，整笔事务回滚 depositNo="
                    + record.getDepositNo(), e);
        }
    }

    /**
     * 事件 + 命中配置 → 充值记录：状态固定“待确认”，确认推进与账务处理由下游/确认任务完成
     */
    private DepositRecord toRecord(Long chainId, TransferEventDTO event, DepositWallet wallet, String source) {
        DepositRecord record = new DepositRecord();
        record.setDepositNo(nextDepositNo());
        record.setChainId(chainId);
        record.setContractAddress(event.contract());
        record.setTransactionHash(event.transactionHash());
        record.setLogIndex(event.logIndex().longValue());
        record.setBlockNumber(event.blockNumber().longValue());
        record.setFromAddress(event.from());
        record.setToAddress(wallet.getAddress());
        record.setWalletType(wallet.getWalletType());
        record.setUserId(wallet.getUserId());
        record.setCurrency(wallet.getCurrency());
        record.setRawValue(event.rawValue().toString());
        record.setDisplayValue(event.displayValue());
        record.setDecimals(event.decimals());
        record.setStatus(DepositConsts.Status.PENDING_CONFIRM);
        record.setEventSource(source);
        record.setNotifyStatus(DepositConsts.Notify.INIT);
        return record;
    }

    private DepositEventVO toMessage(DepositRecord record) {
        return new DepositEventVO(
                record.getDepositNo(),
                record.getChainId(),
                record.getContractAddress(),
                record.getCurrency(),
                record.getTransactionHash(),
                record.getLogIndex(),
                record.getBlockNumber(),
                record.getFromAddress(),
                record.getToAddress(),
                record.getWalletType(),
                record.getUserId(),
                record.getDisplayValue(),
                record.getRawValue(),
                record.getDecimals(),
                record.getStatus(),
                depositProperties.getConfirmThreshold(),
                record.getEventSource(),
                record.getCreatedAt() != null ? record.getCreatedAt() : LocalDateTime.now());
    }

    /**
     * 合约维度校验：配置未限定合约 → 任意代币到账均计充值；限定则必须小写等值命中（防币种串账）
     */
    private boolean contractAccepted(DepositWallet wallet, String contract) {
        if (wallet.getContract() == null || wallet.getContract().isBlank()) {
            return true;
        }
        return wallet.getContract().equalsIgnoreCase(contract);
    }

    /**
     * 充值单号：DP + 时间戳(便于人工排序检索) + 16 位十六进制随机（64 bit）
     * <p>
     * deposit_no 上有唯一键，而 INSERT IGNORE 会把“单号撞车”也当作重复行静默丢弃（丢一笔记账），
     * 故随机量必须大到结构上不可能碰：同一毫秒内批量 1000 条（{@code DbConsts.INSERT_BATCH_SIZE}）的碰撞概率约 3e-14
     */
    private String nextDepositNo() {
        long high = RANDOM.nextLong();
        long low = RANDOM.nextLong();
        return DEPOSIT_NO_PREFIX + DEPOSIT_NO_TIME.format(LocalDateTime.now())
                + String.format("%08x%08x", high, low);
    }
}