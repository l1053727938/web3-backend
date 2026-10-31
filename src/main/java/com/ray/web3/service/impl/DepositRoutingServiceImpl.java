package com.ray.web3.service.impl;

import com.ray.web3.config.DepositProperties;
import com.ray.web3.config.InfuraProperties;
import com.ray.web3.consts.DbConsts;
import com.ray.web3.mapper.DepositRecordMapper;
import com.ray.web3.model.DepositConsts;
import com.ray.web3.model.dto.TransferEventDTO;
import com.ray.web3.model.entity.DepositRecord;
import com.ray.web3.model.entity.DepositWallet;
import com.ray.web3.model.vo.DepositEventVO;
import com.ray.web3.service.DepositRoutingService;
import com.ray.web3.service.DepositWalletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 充值路由实现（REQ-BIZ-04）：热钱包匹配 → 幂等落库（待确认）→ Redis 发布下游通知
 * <p>
 * 关键设计：
 * 1. <b>发布时机</b>：先落库再 PUBLISH。Redis 发布失败只把 notify_status 置 FAILED（不影响已提交的入账），
 *    由补偿任务按状态重发；若反过来"先发布后落库"，落库失败会让下游收到一条不存在的充值，账务对不平。
 * 2. <b>幂等口径</b>：INSERT IGNORE 命中唯一键 (chain_id, txHash, log_index) 返回 0 行即视为重复，
 *    重复事件不再发布——重连重放、补拉分页重叠、实时与补拉双写都只通知一次；
 *    多实例并发同一事件时唯一键冲突会抛异常，回读确认已存在则按重复处理（结果一致，不丢不重）。
 * 3. <b>非事务</b>：本方法刻意不开事务，必须在落库事务<b>提交后</b>调用，否则会出现
 *    "消息已发到下游、充值行随事务回滚消失"的悬挂通知。
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
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final InfuraProperties infuraProperties;
    private final DepositProperties depositProperties;

    @Override
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
     * 单片路由：匹配 → 幂等落库 → 发布，返回本片新增充值记录数
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

        // 2. 幂等落库 + 取回已提交行（含自增 id）：只有新增成功的才进入通知环节
        List<DepositRecord> toPublish = new ArrayList<>(matched.size());
        long inserted = insertIgnoreChunks(matched, chainId, toPublish);

        // 3. 逐条发布下游通知（充值量级远低于事件量，单条发布换取状态可精确补偿）
        long notified = 0;
        for (DepositRecord record : toPublish) {
            if (publishToDownstream(record)) {
                notified++;
            }
        }
        log.info("[deposit] 充值路由 source={} events={} matched={} inserted={} published={}",
                source, events.size(), matched.size(), inserted, notified);
        return inserted;
    }

    /**
     * 分批幂等写入，并把"本次已提交在库"的充值行（含自增 id）收集到 published 供发布
     * <p>
     * INSERT IGNORE 不回填自增 id、也不告知哪一行被忽略，故新增行统一按幂等键回读一次：
     * 回读既能拿到 id（notify_status 需要按行更新），也能过滤掉重放/重叠的重复行
     *
     * @return 实际新增行数
     */
    private long insertIgnoreChunks(List<DepositRecord> records, Long chainId, List<DepositRecord> published) {
        long inserted = 0;
        for (int from = 0; from < records.size(); from += DbConsts.INSERT_BATCH_SIZE) {
            List<DepositRecord> chunk = records.subList(from,
                    Math.min(from + DbConsts.INSERT_BATCH_SIZE, records.size()));
            try {
                inserted += depositRecordMapper.insertIgnoreBatch(chunk);
            } catch (DataIntegrityViolationException e) {
                // 多实例同时路由到同一笔事件时 INSERT IGNORE 仍可能撞上唯一键：逐行回读，已存在则当重复处理
                inserted += recoverConflictingChunk(chunk, chainId, published);
                continue;
            }
            for (DepositRecord record : chunk) {
                DepositRecord stored = depositRecordMapper.selectByTxLog(
                        chainId, record.getTransactionHash(), record.getLogIndex());
                if (stored == null) {
                    log.warn("[deposit] 写入后回读为空（可能已回滚）tx={} logIndex={}",
                            record.getTransactionHash(), record.getLogIndex());
                    continue;
                }
                published.add(stored);
            }
        }
        return inserted;
    }

    /**
     * 唯一键冲突兜底：逐行回读，已存在且金额/地址一致则当作正常入账收集去发布（不重复插行），
     * 不一致则告警等人工裁决（同一幂等键出现不同金额属于链上异常，不能自动覆盖）
     *
     * @return 回读命中（视为已入库）的行数
     */
    private int recoverConflictingChunk(List<DepositRecord> chunk, Long chainId, List<DepositRecord> published) {
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
            published.add(exist);
        }
        return recovered;
    }

    /**
     * PUBLISH deposit_channel：成功则把 notify_status 置 SENT，失败置 FAILED 等补偿重发；
     * 发布异常绝不影响已提交的充值记录
     */
    private boolean publishToDownstream(DepositRecord record) {
        String channel = depositProperties.getDepositChannel();
        try {
            DepositEventVO message = toMessage(record);
            String payload = objectMapper.writeValueAsString(message);
            //todo 没有下游消费
            stringRedisTemplate.convertAndSend(channel, payload);
            markNotifyStatus(record.getId(), DepositConsts.Notify.SENT, LocalDateTime.now());
            log.info("[deposit] 已发布充值通知 channel={} depositNo={} {} amount={} status={}",
                    channel, record.getDepositNo(), record.getToAddress(),
                    record.getDisplayValue(), record.getStatus());
            return true;
        } catch (Exception e) {
            markNotifyStatus(record.getId(), DepositConsts.Notify.FAILED, null);
            log.error("[deposit] 充值通知发布失败（记录已入库，可由补偿任务重发）depositNo={} tx={}: {}",
                    record.getDepositNo(), record.getTransactionHash(), e.getMessage());
            return false;
        }
    }

    /**
     * 只更新通知相关两列（partial updateById 忽略 null 字段），失败不影响主流程
     */
    private void markNotifyStatus(Long id, String status, LocalDateTime publishedAt) {
        if (id == null) {
            return;
        }
        try {
            DepositRecord patch = new DepositRecord();
            patch.setId(id);
            patch.setNotifyStatus(status);
            patch.setNotifyAt(publishedAt);
            depositRecordMapper.updateById(patch);
        } catch (Exception e) {
            log.warn("[deposit] 更新通知状态失败 id={} status={}: {}", id, status, e.getMessage());
        }
    }

    /**
     * 事件 + 命中配置 → 充值记录：状态固定"待确认"，确认推进与账务处理由下游/确认任务完成
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
