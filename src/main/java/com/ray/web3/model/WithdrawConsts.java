package com.ray.web3.model;

/**
 * 提币业务常量（第 3 周：写与发交易）
 * <p>
 * 与 sql/schema.sql 中 withdraw_record.status 的枚举列取值一一对应，
 * 风格对齐 {@link DepositConsts}：集中定义避免落库、状态机、下游消息三处字面量漂移
 */
public final class WithdrawConsts {

    private WithdrawConsts() {
    }

    /** 提币状态（withdraw_record.status）：单向流转，回滚/替换走显式状态而非改写历史 */
    public static final class Status {
        /** 已落库未广播：先落库后调用，广播结果不确定时以本行 nonce 为上链查询锚点 */
        public static final String INIT = "INIT";
        /** 已广播进内存池，等待确认（Day 3-4 的轮询追踪从此状态接管） */
        public static final String BROADCAST = "BROADCAST";
        /** 已上链且 status=0x1，确认数达标后转终态 */
        public static final String SUCCESS = "SUCCESS";
        /** 估算失败 / 节点拒接 / 上链 Revert，error_message 记录原因 */
        public static final String FAILED = "FAILED";
        /** 被同 Nonce 的加速交易覆盖（Replace-by-fee，Day 5-7） */
        public static final String REPLACED = "REPLACED";

        private Status() {
        }
    }

    /** ERC-20 转账函数名（构造 calldata 用，ABI 签名为 transfer(address,uint256)） */
    public static final String ERC20_FUNCTION_TRANSFER = "transfer";

    /** 提币单号前缀（WD + 时间戳 + 随机段，生成口径同充值单号 DP） */
    public static final String WITHDRAW_NO_PREFIX = "WD";
}
