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
