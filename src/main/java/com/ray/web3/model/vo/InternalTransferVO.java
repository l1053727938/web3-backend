package com.ray.web3.model.vo;

import java.math.BigInteger;

/**
 * 内部 ETH 转账视图对象：从 debug_traceTransaction(callTracer) 调用树里提取的一次 {@code value>0} 的 CALL。
 * <p>
 * 与 ERC-20 Transfer 不同，内部交易没有独立 txHash，只存在于交易执行的调用树中；
 * 这类记录用于对账"用户声称已充值但日志未匹配到"的 ETH 到账（旁路补偿，非主监听链路）。
 *
 * @param from         转出地址（小写归一）
 * @param to           转入地址（小写归一）
 * @param rawValue     转移金额（wei，原始 BigInteger）
 * @param displayValue 换算为 ETH 的十进制字符串（18 位精度，与 Etherscan 内部交易展示一致）
 */
public record InternalTransferVO(
        String from,
        String to,
        BigInteger rawValue,
        String displayValue) {
}
