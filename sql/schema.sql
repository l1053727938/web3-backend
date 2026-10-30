-- web3-backend 初始化脚本
CREATE DATABASE IF NOT EXISTS web3_backend DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE web3_backend;

-- 区块扫描进度表（latest_scanned_block 同时充当历史补拉的 lastProcessedBlock 游标：
-- contract_address 为合约地址时，该列值 = 该合约已完整处理到的区块高度，下一轮从 +1 开始）
CREATE TABLE IF NOT EXISTS block_sync_state
(
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    chain_id              BIGINT      NOT NULL COMMENT '链ID，Sepolia=11155111',
    contract_address      VARCHAR(64) NOT NULL DEFAULT '' COMMENT '合约地址，空串表示原生链',
    latest_scanned_block  BIGINT      NOT NULL DEFAULT 0 COMMENT '已处理区块高度（历史补拉游标 lastProcessedBlock）',
    updated_at            DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_chain_contract (chain_id, contract_address)
) ENGINE = InnoDB COMMENT ='区块扫描进度/历史补拉游标';

-- ERC-20 Transfer 事件流水表：历史补拉每一页「事件落库 + 游标推进」同事务，保证不漏单
-- 金额列用变长字符串而非 DECIMAL：MySQL DECIMAL 最大精度 65 位，容不下 uint256（最多 78 位），
-- 监听任意合约时若碰到超大规模金额会整页回滚、游标永久卡住；字符串写入不会失败
CREATE TABLE IF NOT EXISTS transfer_event_log
(
    id               BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    chain_id         BIGINT      NOT NULL COMMENT '链ID，Sepolia=11155111',
    contract_address VARCHAR(64) NOT NULL COMMENT '代币合约地址（小写）',
    block_number     BIGINT      NOT NULL COMMENT '区块高度',
    transaction_hash VARCHAR(80) NOT NULL COMMENT '交易哈希',
    log_index        BIGINT      NOT NULL COMMENT '日志在区块内的序号',
    from_address     VARCHAR(64) NOT NULL COMMENT '转出地址',
    to_address       VARCHAR(64) NOT NULL COMMENT '转入地址',
    raw_value        VARCHAR(78) NOT NULL COMMENT '原始金额（最小单位整数字符串，容纳 uint256 全量）',
    display_value    VARCHAR(80) NOT NULL COMMENT '按代币精度换算后的金额（十进制字符串，保留尾零）',
    decimals         INT         NOT NULL COMMENT '换算所用代币精度（ETH/WRAP 18、USDT/USDC 6）',
    updated_at       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    created_at       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '入库时间',
    UNIQUE KEY uk_chain_tx_log (chain_id, transaction_hash, log_index),
    KEY idx_contract_block (contract_address, block_number)
) ENGINE = InnoDB COMMENT ='ERC-20 Transfer 事件流水';

-- 充值地址表（REQ-BIZ-04 热钱包匹配）：平台热钱包 + 用户充值地址统一维护，实时链路与补拉链路共用
-- 匹配按 to_address 小写等值命中，故唯一键直接落在 (chain_id, address)：同链同地址只允许一条有效配置
CREATE TABLE IF NOT EXISTS deposit_wallet
(
    id            BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    chain_id      BIGINT      NOT NULL COMMENT '链ID，Sepolia=11155111',
    address       VARCHAR(64) NOT NULL COMMENT '充值地址（小写）',
    wallet_type   VARCHAR(16) NOT NULL DEFAULT 'HOT' COMMENT '地址类型：HOT=平台热钱包，USER=用户充值地址',
    user_id       VARCHAR(64)          DEFAULT NULL COMMENT '用户标识，wallet_type=USER 时必填',
    currency      VARCHAR(32)          DEFAULT NULL COMMENT '归属币种符号（如 USDT），仅作展示/对账',
    contract      VARCHAR(64)          DEFAULT NULL COMMENT '限定代币合约（小写）；NULL=任意合约到账均视为该地址充值',
    status        TINYINT     NOT NULL DEFAULT 1 COMMENT '1=启用，0=停用（停用后不再路由新充值）',
    remark        VARCHAR(255)         DEFAULT NULL COMMENT '备注',
    updated_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    created_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_chain_address (chain_id, address),
    KEY idx_chain_type_status (chain_id, wallet_type, status)
) ENGINE = InnoDB COMMENT ='平台热钱包/用户充值地址配置';

-- 充值记录表（REQ-BIZ-04 入账 + 幂等）：唯一键 (chain_id, transaction_hash, log_index) 是幂等的最终防线，
-- 重连重放、补拉区间重叠、实时与补拉双写都靠 INSERT IGNORE 消化，不会产生重复入账
-- 金额列同 transfer_event_log 用字符串：uint256 最多 78 位，MySQL DECIMAL 最大 65 位容不下
CREATE TABLE IF NOT EXISTS deposit_record
(
    id               BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    deposit_no       VARCHAR(64) NOT NULL COMMENT '充值单号（对外业务主键）',
    chain_id         BIGINT      NOT NULL COMMENT '链ID，Sepolia=11155111',
    contract_address VARCHAR(64) NOT NULL COMMENT '代币合约地址（小写）',
    transaction_hash VARCHAR(80) NOT NULL COMMENT '交易哈希（幂等键之一）',
    log_index        BIGINT      NOT NULL COMMENT '日志在区块内的序号（幂等键之一）',
    block_number     BIGINT      NOT NULL COMMENT '入账区块高度，确认数由此与链上最新高度差值算出',
    from_address     VARCHAR(64) NOT NULL COMMENT '转出地址（用户充值来源）',
    to_address       VARCHAR(64) NOT NULL COMMENT '转入地址（命中的平台热钱包/用户充值地址）',
    wallet_type      VARCHAR(16) NOT NULL COMMENT '命中地址类型：HOT/USER',
    user_id          VARCHAR(64)          DEFAULT NULL COMMENT '归属用户，wallet_type=USER 时非空',
    currency         VARCHAR(32)          DEFAULT NULL COMMENT '币种符号',
    raw_value        VARCHAR(78) NOT NULL COMMENT '原始金额（最小单位整数字符串，容纳 uint256 全量）',
    display_value    VARCHAR(80) NOT NULL COMMENT '按代币精度换算后的到账金额（十进制字符串）',
    decimals         INT         NOT NULL COMMENT '换算所用代币精度',
    status           VARCHAR(16) NOT NULL DEFAULT 'PENDING_CONFIRM' COMMENT '充值状态：PENDING_CONFIRM=待确认，CREDITED=已入账，INVALID=已作废（链重组）',
    confirmed_block  BIGINT               DEFAULT NULL COMMENT '达到确认阈值时的区块高度',
    event_source     VARCHAR(16) NOT NULL COMMENT '事件来源：STREAM=WebSocket 实时订阅，BACKFILL=历史补拉',
    notify_status    VARCHAR(16) NOT NULL DEFAULT 'INIT' COMMENT '下游通知状态：INIT/SENT/FAILED（发布失败可据此补偿重发）',
    notify_at        DATETIME             DEFAULT NULL COMMENT '最近一次成功发布时间',
    updated_at       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    created_at       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '入库时间',
    UNIQUE KEY uk_chain_tx_log (chain_id, transaction_hash, log_index),
    UNIQUE KEY uk_deposit_no (deposit_no),
    KEY idx_status_block (status, block_number),
    KEY idx_to_address (chain_id, to_address)
) ENGINE = InnoDB COMMENT ='充值记录（热钱包匹配入账，状态待确认）';
