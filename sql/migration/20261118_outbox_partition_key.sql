-- ============================================================================
-- 2026-11-18：outbox_message 增加 Kafka 分区键
--
-- 目的：同一用户的充值/提现等事件使用同一个 key，保证进入同一 partition 按序消费。
-- 兼容：旧数据 partition_key 为 NULL，投递器回退使用 biz_no；
--       新数据由路由写入 userId（HOT 钱包无归属用户，回退 depositNo）。
-- ============================================================================

USE web3_backend;

ALTER TABLE `outbox_message`
    ADD COLUMN `partition_key` VARCHAR(64) NULL
        COMMENT 'Kafka 分区键：同一用户事件必须相同（通常 userId）；NULL 时投递器回退 biz_no'
        AFTER `biz_no`;

-- 可选：把积压的 DEPOSIT 消息也按 payload 里的 userId 回填（SENT 的历史消息无需处理）
-- UPDATE outbox_message
-- SET partition_key = JSON_UNQUOTE(JSON_EXTRACT(payload, '$.userId'))
-- WHERE biz_type = 'DEPOSIT'
--   AND partition_key IS NULL
--   AND JSON_EXTRACT(payload, '$.userId') IS NOT NULL;