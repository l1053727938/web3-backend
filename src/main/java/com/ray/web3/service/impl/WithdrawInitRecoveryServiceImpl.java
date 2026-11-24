package com.ray.web3.service.impl;

import com.ray.web3.config.WithdrawProperties;
import com.ray.web3.consts.WithdrawConsts;
import com.ray.web3.mapper.WithdrawRecordMapper;
import com.ray.web3.model.entity.WithdrawRecord;
import com.ray.web3.service.WithdrawInitRecoveryService;
import com.ray.web3.withdraw.InitRecoveryPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.EthBlock;
import org.web3j.protocol.core.methods.response.Transaction;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

import java.math.BigInteger;
import java.util.List;

/**
 * INIT 提币接管实现（第 5 周 Day 5-6）
 * <p>
 * 取链上事实的顺序：
 * <ol>
 *   <li>本地有 txHash → 直接查 receipt；有收据即视为已上链；</li>
 *   <li>没有收据/没有 hash → 回扫最近 N 个区块，按 (from, nonce) 找占用该 nonce 的交易；</li>
 *   <li>查 pending nonce，区分“nonce 仍空闲”和“已被消耗但看不到交易”。</li>
 * </ol>
 * 判定交给纯函数 {@link InitRecoveryPolicy}，本类只负责取数与回填。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "web3.withdraw", name = "enabled", havingValue = "true")
public class WithdrawInitRecoveryServiceImpl implements WithdrawInitRecoveryService {

    private static final int ERROR_MESSAGE_MAX_LEN = 500;

    private final WithdrawRecordMapper withdrawRecordMapper;
    private final WithdrawProperties withdrawProperties;
    private final Web3j web3j;

    @Override
    public int recoverOnce() {
        if (!withdrawProperties.isInitRecoveryEnabled()) {
            return 0;
        }
        int batchSize = Math.max(1, withdrawProperties.getInitRecoveryBatchSize());
        List<WithdrawRecord> batch = withdrawRecordMapper.selectByStatus(
                WithdrawConsts.Status.INIT, batchSize);
        if (batch.isEmpty()) {
            return 0;
        }

        int changed = 0;
        for (WithdrawRecord record : batch) {
            try {
                if (recover(record)) {
                    changed++;
                }
            } catch (Exception e) {
                // 单笔 RPC/解析失败不能中断整批，也不改状态：下一轮再判
                log.error("[withdraw-init] 三态判定失败 withdrawNo={} nonce={}: {}",
                        record.getWithdrawNo(), record.getNonce(), e.getMessage(), e);
            }
        }
        if (changed > 0) {
            log.info("[withdraw-init] 本轮处理 {} 条，状态跃迁 {} 条", batch.size(), changed);
        }
        return changed;
    }

    private boolean recover(WithdrawRecord record) throws Exception {
        if (record.getNonce() == null) {
            log.warn("[withdraw-init] INIT 记录缺少 nonce，跳过 withdrawNo={}", record.getWithdrawNo());
            return false;
        }
        ChainFacts facts = inspect(record);
        InitRecoveryPolicy.Decision decision = InitRecoveryPolicy.decide(
                record.getTransactionHash(), facts.foundHash,
                facts.onChain, facts.success,
                facts.pendingNonce, record.getNonce().longValue());

        switch (decision.outcome()) {
            case SUCCESS -> {
                int updated = withdrawRecordMapper.markInitSuccess(
                        record.getWithdrawNo(), facts.foundHash);
                if (updated > 0) {
                    log.info("[withdraw-init] 链上成功，INIT → SUCCESS withdrawNo={} tx={}",
                            record.getWithdrawNo(), facts.foundHash);
                    return true;
                }
            }
            case FAILED -> {
                int updated = withdrawRecordMapper.markInitFailed(
                        record.getWithdrawNo(), facts.foundHash,
                        truncate(decision.reason()));
                if (updated > 0) {
                    log.warn("[withdraw-init] 确定失败，INIT → FAILED withdrawNo={} tx={} reason={}",
                            record.getWithdrawNo(), facts.foundHash, decision.reason());
                    return true;
                }
            }
            case PENDING -> {
                // 推进 updated_at，避免固定几笔 INIT 占满批次队头
                withdrawRecordMapper.touchInit(record.getWithdrawNo());
                log.debug("[withdraw-init] 保持 INIT withdrawNo={} nonce={} reason={}",
                        record.getWithdrawNo(), record.getNonce(), decision.reason());
            }
        }
        return false;
    }

    /**
     * 取链上事实：receipt 为准；没有 receipt 时回扫区块按 (from, nonce) 找占用交易
     */
    private ChainFacts inspect(WithdrawRecord record) throws Exception {
        String recordHash = record.getTransactionHash();
        String foundHash = null;
        boolean onChain = false;
        boolean success = false;

        if (hasText(recordHash)) {
            TransactionReceipt receipt = receipt(recordHash);
            if (receipt != null) {
                onChain = true;
                success = isSuccess(receipt);
                foundHash = recordHash;
            }
        }

        if (!onChain) {
            foundHash = scanByNonce(record);
            if (foundHash != null) {
                TransactionReceipt receipt = receipt(foundHash);
                if (receipt != null) {
                    onChain = true;
                    success = isSuccess(receipt);
                }
            }
        }

        long pendingNonce = pendingNonce(record.getFromAddress());
        return new ChainFacts(onChain, success, foundHash, pendingNonce);
    }

    private TransactionReceipt receipt(String txHash) throws Exception {
        return web3j.ethGetTransactionReceipt(txHash).send()
                .getTransactionReceipt().orElse(null);
    }

    private boolean isSuccess(TransactionReceipt receipt) {
        return "0x1".equalsIgnoreCase(receipt.getStatus());
    }

    /**
     * 回扫最近 N 个区块，找发送方与 nonce 都匹配的交易；找不到返回 null
     */
    private String scanByNonce(WithdrawRecord record) throws Exception {
        int lookback = Math.max(1, withdrawProperties.getInitRecoveryLookbackBlocks());
        BigInteger latest = web3j.ethBlockNumber().send().getBlockNumber();
        for (int i = 0; i < lookback; i++) {
            BigInteger number = latest.subtract(BigInteger.valueOf(i));
            if (number.signum() < 0) {
                break;
            }
            EthBlock block = web3j.ethGetBlockByNumber(
                    DefaultBlockParameter.valueOf(number), true).send();
            if (block.getBlock() == null) {
                continue;
            }
            for (EthBlock.TransactionResult<?> result : block.getBlock().getTransactions()) {
                Transaction tx = (Transaction) result.get();
                if (tx.getFrom() != null && tx.getFrom().equalsIgnoreCase(record.getFromAddress())
                        && tx.getNonce().longValue() == record.getNonce().longValue()) {
                    return tx.getHash();
                }
            }
        }
        return null;
    }

    private long pendingNonce(String address) throws Exception {
        return web3j.ethGetTransactionCount(address, DefaultBlockParameterName.PENDING)
                .send().getTransactionCount().longValue();
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= ERROR_MESSAGE_MAX_LEN
                ? value : value.substring(0, ERROR_MESSAGE_MAX_LEN);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record ChainFacts(boolean onChain, boolean success, String foundHash, long pendingNonce) {
    }
}
