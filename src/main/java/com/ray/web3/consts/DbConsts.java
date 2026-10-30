package com.ray.web3.consts;

/**
 * 数据库写入相关常量
 * <p>
 * 批大小刻意写成常量而不是配置项：它只受单语句拼包上限约束，调它的唯一依据是
 * {@code max_allowed_packet} 与单行字节数，属于容量参数而非业务开关；
 * 放进配置会让各环境取值漂移，出现"同一份代码在不同环境拼出不同大小的包"，
 * 排查丢行/报错时反而多一个变量。流水表与充值表也保持同口径，避免两套分批逻辑。
 */
public final class DbConsts {

    /**
     * 单条 {@code INSERT IGNORE} 语句的最大行数
     * <p>
     * 1000 行按本项目单行宽度（金额两列最长 78+80 字符 + 两个 40 位地址 + 一个 64 位哈希）
     * 估算拼包上限约 300KB，远低于 MySQL 8 默认的 64MB，留出加列的余量；
     * 再往上调就只是在逼近 {@code max_allowed_packet}，收益已经没有了
     */
    public static final int INSERT_BATCH_SIZE = 1000;

    private DbConsts() {
    }
}
