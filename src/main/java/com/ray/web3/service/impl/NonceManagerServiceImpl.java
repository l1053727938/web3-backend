package com.ray.web3.service.impl;

import com.ray.web3.mapper.WalletNonceMapper;
import com.ray.web3.service.NonceManagerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;

import java.math.BigInteger;

/**
 * Nonce 管理服务实现（第 3 周：写与发交易）
 * <p>
 * 分配用 {@code TransactionTemplate} 精确控制事务边界：<b>只在"锁行读 → +1 写回"这段开事务</b>，
 * 链上取种子（RPC）放在事务外——否则 RPC 抖动的几秒会把数据库行锁一直占着，
 * 并发提币全堵在这把锁上，把本地锁竞争放大成跨系统串行
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NonceManagerServiceImpl implements NonceManagerService {

    private static final BigInteger ONE = BigInteger.ONE;

    private final WalletNonceMapper walletNonceMapper;
    private final Web3j web3j;
    private final TransactionTemplate transactionTemplate;

    @Override
    public BigInteger allocateNext(long chainId, String address) {
        String lower = address.toLowerCase();
        ensureSeeded(chainId, lower);
        BigInteger allocated = transactionTemplate.execute(status -> {
            // FOR UPDATE 行锁：并发分配在此串行化，两个线程拿到互不相同的连续 Nonce
            BigInteger current = walletNonceMapper.selectForUpdate(chainId, lower);
            if (current == null) {
                throw new IllegalStateException("wallet_nonce 行在锁定期被删除，address=" + lower);
            }
            walletNonceMapper.updateNext(chainId, lower, current.add(ONE));
            return current;
        });
        log.debug("[withdraw] 分配 Nonce chain={} addr={} nonce={}", chainId, lower, allocated);
        return allocated;
    }

    @Override
    public BigInteger peek(long chainId, String address) {
        String lower = address.toLowerCase();
        ensureSeeded(chainId, lower);
        return walletNonceMapper.selectCurrent(chainId, lower);
    }

    @Override
    public BigInteger alignToChain(long chainId, String address) {
        String lower = address.toLowerCase();
        ensureSeeded(chainId, lower);
        BigInteger chainNonce = chainPendingNonce(lower);
        return transactionTemplate.execute(status -> {
            BigInteger current = walletNonceMapper.selectForUpdate(chainId, lower);
            // 只前进：链上口径领先游标（系统外发过交易）才推平，落后则不动，防把已用 Nonce 重新放出
            if (current == null || chainNonce.compareTo(current) > 0) {
                walletNonceMapper.updateNext(chainId, lower, chainNonce);
                log.warn("[withdraw] Nonce 游标上推对齐链上 chain={} addr={} db={} chain={}",
                        chainId, lower, current, chainNonce);
                return chainNonce;
            }
            return current;
        });
    }

    @Override
    public void reallocateAfterReject(long chainId, String address) {
        String lower = address.toLowerCase();
        BigInteger chainNonce = chainPendingNonce(lower);
        transactionTemplate.execute(status -> {
            BigInteger current = walletNonceMapper.selectForUpdate(chainId, lower);
            // 确定性拒绝：交易没进内存池，游标可安全退回链上 pending 复用该 Nonce，避免留下空洞
            if (current != null && chainNonce.compareTo(current) < 0) {
                walletNonceMapper.updateNext(chainId, lower, chainNonce);
                log.info("[withdraw] 广播被拒，Nonce 游标回拨复用 chain={} addr={} from={} to={}",
                        chainId, lower, current, chainNonce);
            }
            return null;
        });
    }

    @Override
    public BigInteger chainPendingNonce(String address) {
        try {
            return web3j.ethGetTransactionCount(address, DefaultBlockParameterName.PENDING)
                    .send().getTransactionCount();
        } catch (Exception e) {
            throw new IllegalStateException("查询链上 pending Nonce 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 幂等确保游标行存在：不存在则以链上 pending 为种子 INSERT IGNORE；
     * 事务外调用，行缺失才发 RPC，命中已有行零开销（绝大多数分配走这条快路径）
     */
    private void ensureSeeded(long chainId, String lowerAddress) {
        if (walletNonceMapper.selectCurrent(chainId, lowerAddress) == null) {
            BigInteger seed = chainPendingNonce(lowerAddress);
            int inserted = walletNonceMapper.insertIgnore(chainId, lowerAddress, seed);
            log.info("[withdraw] 初始化 Nonce 游标 chain={} addr={} seed={} inserted={}",
                    chainId, lowerAddress, seed, inserted);
        }
    }
}
