package com.ray.web3.service.impl;

import com.ray.web3.config.WithdrawProperties;
import com.ray.web3.mapper.WithdrawRecordMapper;
import com.ray.web3.consts.WithdrawConsts;
import com.ray.web3.model.entity.WithdrawRecord;
import com.ray.web3.service.NonceManagerService;
import com.ray.web3.service.WithdrawTrackingService;
import com.ray.web3.util.ChainQueryUtils;
import com.ray.web3.util.TransactionSigner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.protocol.core.methods.response.EthGetTransactionReceipt;
import org.web3j.protocol.core.methods.response.EthTransaction;
import org.web3j.protocol.core.methods.response.Transaction;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

import java.math.BigInteger;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 提币状态追踪实现（第 3 周 Day 3-4）
 * <p>
 * 无状态、幂等：跃迁全靠 Mapper 的"仅从 BROADCAST 流转"条件 UPDATE 兜底——即便多实例/重跑对同一笔
 * 同时查到收据并回写，也只有第一个 {@code UPDATE ... WHERE status='BROADCAST'} 命中，其余返回 0 行不改状态，
 * 因此不需要额外分布式锁保证正确性（扫描器上的 Redisson 锁只是省掉重复 RPC 开销，非正确性依赖）。
 * <p>
 * RPC 失败一律"保持 BROADCAST 不猜测"：查不到收据/网络异常都留给下一轮，绝不在结果不确定时误判终态
 * ——这与广播层"超时不回拨 Nonce、保留锚点"是同一条不确定性处理原则
 * <p>
 * 终态门槛对称：{@code SUCCESS} 与 {@code FAILED} 都必须先过 {@code track-confirmations} 确认数门槛才落库
 * ——收据 status 只代表当前节点的规范链视图，浅块 Revert 可能因重组变成成功，而 FAILED 会触发不可逆的解冻退款
 * <p>
 * 针对"永远不中块 / 永远凑不够确认数"这类活性问题，本类只做到"发现与分类"（自动提价重播在
 * {@code WithdrawAccelerateServiceImpl}，不在本类里抢跑）：
 * <ul>
 *   <li>{@link WithdrawRecordMapper#touchBroadcast} 心跳：未出结果的记录回写 {@code updated_at} 排到队尾，
 *       否则卡单会因 {@code updated_at} 永不变化而永久霸占 {@code selectByStatus} 队头，满 batch 后把新交易的
 *       确认推进完全饿死；</li>
 *   <li>卡单标注：{@code BROADCAST} 年龄（算 {@code created_at}，不算已被心跳占用的 {@code updated_at}）超过
 *       {@code track-stuck-timeout-ms} 时，诊断成因并一次性写进 {@code error_message}，供告警与后续自动加速取用；</li>
 *   <li>{@link #diagnoseStuck} 只读三分判定，不碰状态机。</li>
 *   <li>Revert 原因挖掘（Day 5-7）：收据不带 revertReason 时用 {@code eth_call} 在原交易所在块的
 *       <b>前一块</b>状态上重放一次，把合约 {@code require} 的原文拿回来写进 {@code error_message}。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "web3.withdraw", name = "enabled", havingValue = "true")
public class WithdrawTrackingServiceImpl implements WithdrawTrackingService {

    /** error_message 落库长度上限：与 schema 中 VARCHAR(512) 对齐，预留冗余截断防超长写入报错 */
    private static final int ERROR_MESSAGE_MAX_LEN = 500;

    /** 节点返回的 revert 通用前缀（大小写不敏感比较），重放取因后需剔掉才知道合约自己说了什么 */
    private static final String PREFIX_EXECUTION_REVERTED = "execution reverted";

    private final Web3j web3j;
    private final WithdrawRecordMapper withdrawRecordMapper;
    private final WithdrawProperties withdrawProperties;
    /** 诊断"同 Nonce 是否已被别的交易消耗"需要链上 pending 口径，复用 Nonce 管理服务已有的封装 */
    private final NonceManagerService nonceManagerService;

    @Override
    public int trackPendingBroadcasts() {
        List<WithdrawRecord> batch = withdrawRecordMapper.selectByStatus(
                WithdrawConsts.Status.BROADCAST, withdrawProperties.getTrackBatchSize());
        if (batch.isEmpty()) {
            return 0;
        }
        // 需要确认数门槛时，本轮只取一次链高复用（省去每条记录一次 eth_blockNumber）
        BigInteger latest = withdrawProperties.getTrackConfirmations() > 0 ? safeLatestBlock() : null;
        int transitions = 0;
        for (WithdrawRecord record : batch) {
            try {
                if (advance(record, latest)) {
                    transitions++;
                } else {
                    observeUnresolved(record);
                }
            } catch (Exception e) {
                // 单条异常不影响整轮：状态没跃迁，下一轮自动重来
                log.warn("[withdraw-track] 单条追踪异常，保持 BROADCAST withdrawNo={} : {}",
                        record.getWithdrawNo(), e.getMessage());
            }
        }
        log.debug("[withdraw-track] 本轮扫描 BROADCAST={} 跃迁={}", batch.size(), transitions);
        return transitions;
    }

    @Override
    public WithdrawRecord trackOne(String withdrawNo) {
        WithdrawRecord record = withdrawRecordMapper.selectByWithdrawNo(withdrawNo);
        if (record == null) {
            return null;
        }
        // 仅追踪待确认态；INIT/终态原样返回（不越状态机权限去动别的阶段的状态）
        if (!WithdrawConsts.Status.BROADCAST.equals(record.getStatus())) {
            return record;
        }
        BigInteger latest = withdrawProperties.getTrackConfirmations() > 0 ? safeLatestBlock() : null;
        try {
            advance(record, latest);
        } catch (Exception e) {
            log.warn("[withdraw-track] 单笔追踪异常，保持 BROADCAST withdrawNo={} : {}", withdrawNo, e.getMessage());
        }
        return withdrawRecordMapper.selectByWithdrawNo(withdrawNo);
    }

    /**
     * 查收据并按结果推进单笔状态；返回是否发生跃迁（转 SUCCESS/FAILED）。
     * <p>
     * 未上块、确认不足、RPC/节点错误都原样返回 false（保持 BROADCAST），交由下一轮重试
     */
    private boolean advance(WithdrawRecord record, BigInteger latest) {
        String txHash = record.getTransactionHash();
        if (txHash == null || txHash.isBlank()) {
            // BROADCAST 理应有 txHash（markBroadcast 回填），缺失属异常数据，保守跳过不误判
            log.warn("[withdraw-track] BROADCAST 记录缺 txHash，跳过 withdrawNo={}", record.getWithdrawNo());
            return false;
        }

        EthGetTransactionReceipt response;
        try {
            response = web3j.ethGetTransactionReceipt(txHash).send();
        } catch (Exception e) {
            log.debug("[withdraw-track] 查询收据网络异常，保持 BROADCAST tx={} : {}", txHash, e.getMessage());
            return false;
        }
        if (response.hasError()) {
            log.warn("[withdraw-track] 节点返回错误，保持 BROADCAST tx={} : {}",
                    txHash, response.getError().getMessage());
            return false;
        }

        Optional<TransactionReceipt> receiptOpt = response.getTransactionReceipt();
        if (receiptOpt.isEmpty()) {
            log.debug("[withdraw-track] 交易仍未上块（Pending），保持 BROADCAST tx={}", txHash);
            return false;
        }
        TransactionReceipt receipt = receiptOpt.get();

        // 确认数门槛对成败一视同仁，必须卡在 status 判定之前（防 FAILED 误判双花，见下方方法注释）
        if (!confirmationsSettled(record, receipt, latest)) {
            return false;
        }

        // 执行 Revert：status=0x0 → FAILED（已经确认数门槛，不再拿浅块结论写不可逆终态）
        if (!receipt.isStatusOK()) {
            String reason = resolveRevertReason(record, receipt);
            int updated = withdrawRecordMapper.markReverted(record.getWithdrawNo(), truncate(reason));
            if (updated > 0) {
                log.warn("[withdraw-track] 交易上链 Revert 且确认达标 → FAILED withdrawNo={} tx={} reason={}",
                        record.getWithdrawNo(), txHash, reason);
            }
            return updated > 0;
        }

        int updated = withdrawRecordMapper.markSuccess(record.getWithdrawNo());
        if (updated > 0) {
            log.info("[withdraw-track] 交易确认达标 → SUCCESS withdrawNo={} tx={} nonce={}",
                    record.getWithdrawNo(), txHash, record.getNonce());
        }
        return updated > 0;
    }

    /**
     * 收据是否已足够最终：确认数达标才允许落终态，{@code SUCCESS} 与 {@code FAILED} 共用同一道门槛
     * <p>
     * 为何 FAILED 也要等：收据里的 status 只代表<b>当前节点认定的规范链</b>，区块重组、节点短暂分叉、
     * 自建节点重启丢块都可能使同一个 txHash 的收据换成另一条链上的那份，0x0 可变 0x1。
     * 而 FAILED 在业务侧触发解冻退款（一笔新的资金流出，不可追），浅块 Revert 就写 FAILED 会导致
     * “链上实际付出 + 账本退款”双花；SUCCESS 早判至少还能靠对账纠偏，所以两道门槛必须对称
     * <p>
     * {@code track-confirmations=0} 时不判定（即时落终态，仅供测试链复现）；
     * 高度信息缺失或重组导致区块高度回退（minedBlock &gt; latest）一律判不足，宁可多等一轮
     */
    private boolean confirmationsSettled(WithdrawRecord record, TransactionReceipt receipt, BigInteger latest) {
        long required = withdrawProperties.getTrackConfirmations();
        if (required <= 0) {
            return true;
        }
        BigInteger minedBlock = receipt.getBlockNumber();
        if (minedBlock == null || latest == null) {
            return false; // 高度信息不足以判定，等下一轮
        }
        long confirmations = latest.subtract(minedBlock).longValue() + 1;
        if (confirmations < required) {
            log.debug("[withdraw-track] 确认不足保持 BROADCAST withdrawNo={} conf={}/{} status={}",
                    record.getWithdrawNo(), confirmations, required, receipt.getStatus());
            return false;
        }
        return true;
    }

    /**
     * 本轮没得出终态时的两个动作：心跳轮换（防卡单把扫描批次饿死）+ 超时卡单首次标注。
     * <p>
     * 只写 {@code updated_at} 与 {@code error_message}，都碰不到 status，所以不会把"卡住"误写成终态
     */
    private void observeUnresolved(WithdrawRecord record) {
        withdrawRecordMapper.touchBroadcast(record.getWithdrawNo());

        long timeout = withdrawProperties.getTrackStuckTimeoutMs();
        long ageMs = elapsedMs(record.getCreatedAt());
        if (timeout <= 0 || ageMs < timeout) {
            return; // 未超阈：属正常等待，不打扰库
        }
        if (record.getErrorMessage() != null) {
            return; // 已标注过：本行内存的就是上次诊断结果，不重复花 RPC 配额
        }
        StuckState state = diagnose(record);
        if (state == StuckState.NODE_UNAVAILABLE) {
            return; // 成因都问不到，写进去只会是错误结论，等下轮
        }
        // markStuck 带 error_message IS NULL 条件：每笔只标注一次，后续轮次返 0 不重复写
        int marked = withdrawRecordMapper.markStuck(record.getWithdrawNo(), truncate(
                "卡单待处理：成因=" + state + " 年龄=" + ageMs + "ms 阈值=" + timeout
                        + "ms nonce=" + record.getNonce()));
        if (marked > 0) {
            log.warn("[withdraw-track] 卡单标注 withdrawNo={} state={} tx={} nonce={} age={}ms",
                    record.getWithdrawNo(), state, record.getTransactionHash(), record.getNonce(), ageMs);
        }
    }

    @Override
    public StuckState diagnoseStuck(String withdrawNo) {
        WithdrawRecord record = withdrawRecordMapper.selectByWithdrawNo(withdrawNo);
        if (record == null || !WithdrawConsts.Status.BROADCAST.equals(record.getStatus())) {
            return null;
        }
        return diagnose(record);
    }

    /**
     * 链上实况分类：先查交易本体、后查 nonce 消耗，两条信息交叉才能区分"排队/被丢弃/被替换"
     * <p>
     * 全程只读，异常不抛——诊断失败最多是"无法判定"，不能打断调用方的追踪主流程
     */
    private StuckState diagnose(WithdrawRecord record) {
        String txHash = record.getTransactionHash();
        if (txHash == null || txHash.isBlank()) {
            return StuckState.NODE_UNAVAILABLE;
        }
        try {
            EthTransaction response = web3j.ethGetTransactionByHash(txHash).send();
            if (response.hasError()) {
                return StuckState.NODE_UNAVAILABLE;
            }
            Optional<Transaction> tx = response.getTransaction();
            if (tx.isPresent()) {
                // 查得到交易：无 blockHash = 还在内存池排队；有 blockHash = 已中块，只是收据/确认未到位
                String blockHash = tx.get().getBlockHash();
                return (blockHash == null || blockHash.isBlank())
                        ? StuckState.QUEUED
                        : StuckState.AWAITING_CONFIRMATION;
            }
            // 交易已从节点视野消失：看该 nonce 是否已被别的交易消耗——消耗了 = 本笔被覆盖，没消耗 = 本笔被丢弃
            BigInteger pendingNonce = nonceManagerService.chainPendingNonce(record.getFromAddress());
            return pendingNonce.compareTo(record.getNonce()) > 0
                    ? StuckState.REPLACED
                    : StuckState.DROPPED;
        } catch (Exception e) {
            log.debug("[withdraw-track] 诊断失败 withdrawNo={} : {}", record.getWithdrawNo(), e.getMessage());
            return StuckState.NODE_UNAVAILABLE;
        }
    }

    /** 记录年龄(ms)：基于 {@code created_at} 而非 {@code updated_at}，后者已被心跳占用为"最后观察时间" */
    private long elapsedMs(LocalDateTime createdAt) {
        return createdAt == null ? 0L : Duration.between(createdAt, LocalDateTime.now()).toMillis();
    }

    /**
     * 取 Revert 的具体原因（Day 5-7 异常捕获）：收据里带 revertReason 就直接用（多数公共节点不带），
     * 否则重放一次再退到通用文案
     * <p>
     * 重放只是补一份原因文本：拿不到也照写 FAILED 不误，“因为缺原因就不落终态”会把成功已知失败的交易吊住
     */
    private String resolveRevertReason(WithdrawRecord record, TransactionReceipt receipt) {
        String fromReceipt = receipt.getRevertReason();
        if (fromReceipt != null && !fromReceipt.isBlank()) {
            return fromReceipt;
        }
        String replayed = replayForRevertReason(record, receipt);
        return (replayed != null && !replayed.isBlank())
                ? replayed
                : "上链执行 Revert（receipt.status=0x0，节点未提供具体原因）";
    }

    /**
     * 用 {@code eth_call} 重放原交易取 revert 原因：按 (from, to, data, gas, gasPrice, value) 逐字段复现，
     * 并在<b>本交易所在块的前一块</b>状态上执行
     * <p>
     * 为何是 {@code blockNumber - 1}：{@code eth_call} 是在“该块应用完之后”的状态上跑，若直接在本交易
     * 所在块上重放，它已被执行过一遍（余额已扣、状态已变），算出的就不是当时那次失败的原因
     * <p>
     * 全链只读，失败（历史状态被裁、节点不支持块参数、重放竟通过）一律返回 null 由调用方退通用文案
     */
    private String replayForRevertReason(WithdrawRecord record, TransactionReceipt receipt) {
        BigInteger minedBlock = receipt.getBlockNumber();
        if (minedBlock == null || minedBlock.signum() <= 0) {
            return null;
        }
        try {
            // 与首广播同口径重建 calldata：拿不到原始 data 只能重编，而金额/收款人都在本行里存着
            String data = TransactionSigner.encodeErc20Transfer(record.getContractAddress(),
                    record.getToAddress(), new BigInteger(record.getRawValue()));
            // request.Transaction 与已导入的 response.Transaction 同名，此处用全限定名避开冲突
            org.web3j.protocol.core.methods.request.Transaction call =
                    org.web3j.protocol.core.methods.request.Transaction.createFunctionCallTransaction(
                            record.getFromAddress(), null, record.getGasPrice(), record.getGasLimit(),
                            record.getContractAddress(), BigInteger.ZERO, data);
            EthCall response = web3j.ethCall(call,
                    DefaultBlockParameter.valueOf(minedBlock.subtract(BigInteger.ONE))).send();
            if (!response.isReverted()) {
                // 重放竟然通过了：后续区块已改变合约状态，本次失败原因无法复现
                log.debug("[withdraw-track] 重放未复现 Revert，退回通用文案 withdrawNo={} block={}",
                        record.getWithdrawNo(), minedBlock);
                return null;
            }
            return stripExecutionReverted(response.getRevertReason());
        } catch (Exception e) {
            log.debug("[withdraw-track] eth_call 重放取 revert 原因失败 withdrawNo={} : {}",
                    record.getWithdrawNo(), e.getMessage());
            return null;
        }
    }

    /** 去掉节点错误对象里的通用前缀 {@code execution reverted:}，只留合约 require 的原因文本 */
    private String stripExecutionReverted(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.toLowerCase().startsWith(PREFIX_EXECUTION_REVERTED + ":")
                ? trimmed.substring(PREFIX_EXECUTION_REVERTED.length() + 1).trim()
                : trimmed;
    }

    /** 取链最新高度，失败返回 null（追踪场景重跑一轮比多等退避便宜，不抛断整轮） */
    private BigInteger safeLatestBlock() {
        try {
            return ChainQueryUtils.latestBlockNumber(web3j);
        } catch (Exception e) {
            log.warn("[withdraw-track] 取最新区块高度失败，本轮跳过确认数判定: {}", e.getMessage());
            return null;
        }
    }

    /** 截断错误原因到落库长度上限，防 revertReason 超长导致 UPDATE 写入失败 */
    private String truncate(String message) {
        return message.length() <= ERROR_MESSAGE_MAX_LEN
                ? message
                : message.substring(0, ERROR_MESSAGE_MAX_LEN);
    }
}
