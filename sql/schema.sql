-- web3-backend 初始化脚本
CREATE DATABASE IF NOT EXISTS web3_backend DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE web3_backend;

-- 区块扫描进度表
CREATE TABLE IF NOT EXISTS block_sync_state
(
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    chain_id              BIGINT      NOT NULL COMMENT '链ID，Sepolia=11155111',
    contract_address      VARCHAR(64) NOT NULL DEFAULT '' COMMENT '合约地址，空串表示原生链',
    latest_scanned_block  BIGINT      NOT NULL DEFAULT 0 COMMENT '已扫描区块高度',
    updated_at            DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_chain_contract (chain_id, contract_address)
) ENGINE = InnoDB COMMENT ='区块扫描进度';
