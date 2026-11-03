package com.ray.web3.service.impl;

import com.ray.web3.config.InfuraProperties;
import com.ray.web3.config.WithdrawProperties;
import com.ray.web3.consts.PatternConsts;
import com.ray.web3.mapper.WithdrawRecordMapper;
import com.ray.web3.model.WithdrawConsts;
import com.ray.web3.model.entity.WithdrawRecord;
import com.ray.web3.service.Erc20WithdrawService;
import com.ray.web3.service.GasEstimateService;
import com.ray.web3.service.NonceManagerService;
import com.ray.web3.util.TransactionSigner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.response.EthSendTransaction;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * ERC-20 提币交易服务实现（第 3 周：写与发交易）
 * <p>
 * 步骤顺序刻意设计，保证任何失败点都不破坏 Nonce 一致性：
 * <ol>
 *   <li>私钥/入参校验放最前——纯本地检查，失败不消耗任何链上/DB 状态；</li>
 *   <li>Gas 估算在 Nonce 分配<b>之前</b>——估算 Revert 属常态业务失败，此时未占 Nonce，无状态需要回滚；</li>
 *   <li>先落库（INIT）再广播——广播超时结果不确定时，withdraw_record 里的 nonce 就是上链查询的锚点，
 *       绝不能"广播了却没记录"（第 5.5 周接口结果不确定专题的落地）；</li>
 *   <li>广播被节点<b>确定性拒绝</b>才回拨 Nonce 游标；超时/连接类失败一律不回拨——
 *       交易可能已进内存池，回拨复用 = 双花风险。</li>
 * </ol>
 * 确认追踪（BROADCAST→SUCCESS/FAILED）为 Day 3-4 交付，Replace-by-fee 加速为 Day 5-7 交付
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "web3.withdraw", name = "enabled", havingValue = "true")
public class Erc20WithdrawServiceImpl implements Erc20WithdrawService {

    /** 提币单号时间段的格式：与充值单号同口径，便于人工按时间检索 */
    private static final DateTimeFormatter NO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Web3j web3j;
    private final InfuraProperties infuraProperties;
    private final WithdrawProperties withdrawProperties;
    private final GasEstimateService gasEstimateService;
    private final NonceManagerService nonceManagerService;
    private final WithdrawRecordMapper withdrawRecordMapper;

    /** 私钥凭证：首次使用时本地构建并缓存，全程只驻留内存（生产切 KMS 时替换此字段的构建来源） */
    private volatile Credentials credentials;

    @Override
    public WithdrawRecord submitWithdraw(String tokenContract, String recipient, BigInteger rawValue) {
        long chainId = requireChainId();
        Credentials creds = resolveCredentials();
        String from = TransactionSigner.addressOf(creds);
        String contract = normalizeAddress(tokenContract, "代币合约地址");
        String to = normalizeAddress(recipient, "提币目标地址");
        if (rawValue == null || rawValue.signum() <= 0) {
            throw new IllegalArgumentException("提币金额必须为正整数（最小单位）");
        }

        // ① 构造 calldata：transfer(to, value)，value 恒为 0、金额在 data 里
        String data = TransactionSigner.encodeErc20Transfer(contract, to, rawValue);

        // ② Gas 价格与用量估算：余额不足/合约将 Revert 在这里就被节点拦下（此时未占 Nonce，失败零副作用）
        BigInteger gasPrice = gasEstimateService.currentGasPrice();
        BigInteger gasLimit = gasEstimateService.estimateGasLimit(from, contract, data);

        // 提币单号：全局唯一标识一笔提币交易，DB 唯一键、上链查询的锚点
        String withdrawNo = nextWithdrawNo();
        // ③ Nonce 分配：行锁串行化，并发提币各拿唯一值
        BigInteger nonce = nonceManagerService.allocateNext(chainId, from);

        // ④ 先落库 INIT：nonce/gas 一并持久化；此后任何环节崩溃，都能凭 (from, nonce) 在链上找回这笔交易
        WithdrawRecord record = insertInitRecord(withdrawNo, chainId, from, contract, to,
                rawValue, nonce, gasLimit, gasPrice);

        // ⑤ 本地离线签名 + 广播（私钥不出网，出去只有签名后的裸交易 hex）
        String signedHex = TransactionSigner.sign(
                TransactionSigner.buildErc20Transfer(nonce, gasPrice, gasLimit, contract, data),
                chainId, creds);
        return broadcast(record, signedHex);
    }

    @Override
    public WithdrawRecord queryWithdraw(String withdrawNo) {
        WithdrawRecord record = withdrawRecordMapper.selectByWithdrawNo(withdrawNo);
        if (record == null) {
            throw new IllegalArgumentException("提币记录不存在: " + withdrawNo);
        }
        return record;
    }

    /**
     * 广播并处理三种结局：成功（回填 txHash → BROADCAST）、节点确定性拒绝（FAILED + Nonce 游标回拨）、
     * 网络层失败结果不确定（保留 INIT、不回拨 Nonce，等 Day 3-4 追踪以 (from,nonce) 锚点判定）
     */
    private WithdrawRecord broadcast(WithdrawRecord record, String signedHex) {
        EthSendTransaction response;
        try {
            response = web3j.ethSendRawTransaction(signedHex).send();
        } catch (Exception e) {
            String msg = "广播结果不确定（网络/超时），保留 INIT 锚点 nonce=" + record.getNonce()
                    + "，待上链查询判定: " + e.getMessage();
            log.error("[withdraw] {} withdrawNo={}", msg, record.getWithdrawNo(), e);
            throw new IllegalStateException(msg, e);
        }
        if (response.hasError()) {
            // 明确拒接 = 交易确定没进内存池：落 FAILED 并回拨游标复用 Nonce，防空洞卡后续
            String reason = response.getError().getMessage();
            withdrawRecordMapper.markFailed(record.getWithdrawNo(), reason);
            nonceManagerService.reallocateAfterReject(record.getChainId(), record.getFromAddress());
            log.warn("[withdraw] 广播被节点拒绝 withdrawNo={} nonce={} reason={}",
                    record.getWithdrawNo(), record.getNonce(), reason);
            throw new IllegalStateException("广播被节点拒绝: " + reason);
        }

        String txHash = response.getTransactionHash();
        withdrawRecordMapper.markBroadcast(record.getWithdrawNo(), txHash,
                WithdrawConsts.Status.BROADCAST);
        record.setTransactionHash(txHash);
        record.setStatus(WithdrawConsts.Status.BROADCAST);
        log.info("[withdraw] 广播成功 withdrawNo={} tx={} nonce={} gasLimit={} gasPrice={}",
                record.getWithdrawNo(), txHash, record.getNonce(), record.getGasLimit(), record.getGasPrice());
        return record;
    }

    /**
     * 落库 INIT 记录；DB 写失败时交易必然还未广播（写库在广播之前），回拨游标防 Nonce 空洞后原样抛错
     */
    private WithdrawRecord insertInitRecord(String withdrawNo, long chainId, String from, String contract,
                                            String to, BigInteger rawValue, BigInteger nonce,
                                            BigInteger gasLimit, BigInteger gasPrice) {
        WithdrawRecord record = new WithdrawRecord();
        record.setWithdrawNo(withdrawNo);
        record.setChainId(chainId);
        record.setContractAddress(contract);
        record.setFromAddress(from);
        record.setToAddress(to);
        record.setRawValue(rawValue.toString());
        record.setNonce(nonce);
        record.setGasLimit(gasLimit);
        record.setGasPrice(gasPrice);
        record.setStatus(WithdrawConsts.Status.INIT);
        try {
            withdrawRecordMapper.insert(record);
        } catch (RuntimeException e) {
            nonceManagerService.reallocateAfterReject(chainId, from);
            log.error("[withdraw] INIT 落库失败，已回拨 Nonce 游标 withdrawNo={} nonce={}",
                    withdrawNo, nonce, e);
            throw e;
        }
        return record;
    }

    /** 私钥 → 凭证（懒建 + 格式前置校验：宁可启动即失败，不留到签名一半才炸出半状态交易） */
    private Credentials resolveCredentials() {
        Credentials current = credentials;
        if (current == null) {
            synchronized (this) {
                current = credentials;
                if (current == null) {
                    if (!PatternConsts.isPrivateKey(withdrawProperties.getHotWalletPrivateKey())) {
                        throw new IllegalStateException(
                                "web3.withdraw.enabled=true 必须配置合法热钱包私钥（环境变量注入，禁止明文入库）");
                    }
                    current = TransactionSigner.credentialsOf(withdrawProperties.getHotWalletPrivateKey());
                    credentials = current;
                }
            }
        }
        return current;
    }

    private long requireChainId() {
        Long chainId = infuraProperties.getChainId();
        if (chainId == null || chainId <= 0) {
            throw new IllegalStateException("未配置 web3.infura.chain-id，无法完成 EIP-155 签名");
        }
        return chainId;
    }

    /** 地址宽松校验（0x/0X 均可）后归一小写——落库与 RPC 透传统一小写口径，同充值侧规范 */
    private String normalizeAddress(String address, String label) {
        if (!PatternConsts.isLooseAddress(address)) {
            throw new IllegalArgumentException(label + "格式非法：需 0x/0X 前缀 + 40 位十六进制");
        }
        return address.toLowerCase();
    }

    /** 提币单号：WD + 时间戳 + 16 位十六进制随机（64bit），生成口径同充值单号，唯一键兜底 */
    private String nextWithdrawNo() {
        return WithdrawConsts.WITHDRAW_NO_PREFIX + NO_TIME.format(LocalDateTime.now())
                + String.format("%016x", RANDOM.nextLong());
    }
}
