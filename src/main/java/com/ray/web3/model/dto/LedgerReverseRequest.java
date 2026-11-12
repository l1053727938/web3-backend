package com.ray.web3.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 冲正请求（第 2 周 Day 5-6）：{@code LedgerService.reverse()} 的 HTTP 入口
 * <p>
 * 按 {@code (bizType, bizNo)} 找到原分录组，写一组方向相反的新分录，
 * {@code reverse_of} 指向原 entry；原分录永远不修改、不删除。
 * 冲正允许把余额扣成负数，这是更正既成事实的唯一合法路径。
 *
 * @param bizType 原分录的业务类型：DEPOSIT/TRANSFER/FREEZE/UNFREEZE/FEE
 * @param bizNo   原分录的业务单号
 */
public record LedgerReverseRequest(
        @NotBlank(message = "bizType 不能为空")
        @Size(max = 32, message = "bizType 不能超过 32 字符")
        String bizType,

        @NotBlank(message = "bizNo 不能为空")
        @Size(max = 128, message = "bizNo 不能超过 128 字符")
        String bizNo) {
}