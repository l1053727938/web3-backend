-- ============================================================================
-- 第 3 周 Day 5-6：Kafka 端到端充值演示数据
--
-- 前置：
--   1. mysql -u root -p < sql/schema.sql            （consumer_biz / outbox_message 表）
--   2. mysql -u root -p < sql/ledger_test_data.sql  （用户 900001 / 平台负债 900101）
--
-- 作用：
--   登记一个 USER 类型充值地址，命中后：
--     链上 Transfer → deposit_record(user_id=10001) → outbox_message
--     → Kafka deposit-outbox → 消费者按 userId=10001 + USDT 找到 account 900001
--     → LedgerService.deposit() 记：用户 available + / 平台代收负债 -
--
-- 演示地址：Anvil 默认账户 #2（0x3C44...93BC），合约用项目默认本地 USDT 地址。
-- 给该地址转任意 USDT，即触发一笔用户充值。
-- ============================================================================

USE web3_backend;

INSERT INTO deposit_wallet
    (chain_id, address, wallet_type, user_id, currency, contract, status, remark)
VALUES
    (31337,
     '0x3c44cdddb6a900fa2b585dd299e03d12fa4293bc',
     'USER',
     '10001',
     'USDT',
     '0x5fbdb2315678afecb367f032d93f642f64180aa3',
     1,
     'Kafka 端到端演示：Anvil 账户#2')
AS new
ON DUPLICATE KEY UPDATE
    wallet_type = new.wallet_type,
    user_id     = new.user_id,
    currency    = new.currency,
    contract    = new.contract,
    status      = new.status,
    remark      = new.remark;

-- 核对地址已生效（应用会按 wallet-cache-refresh-ms 定期回源，或调 POST /api/chain/deposit/wallets/refresh 立即刷新）
-- SELECT id, chain_id, address, wallet_type, user_id, currency, contract, status
-- FROM deposit_wallet
-- WHERE address = '0x3c44cdddb6a900fa2b585dd299e03d12fa4293bc';

-- 端到端完成后核对：
-- SELECT deposit_no, user_id, currency, display_value, status, notify_status
-- FROM deposit_record ORDER BY id DESC LIMIT 5;
-- SELECT id, biz_type, biz_no, status, retry_count, next_retry_at
-- FROM outbox_message ORDER BY id DESC LIMIT 5;
-- SELECT * FROM consumer_biz ORDER BY consumed_at DESC LIMIT 5;
-- SELECT id, entry_no, biz_type, biz_no, status, reverse_of
-- FROM ledger_entry ORDER BY id DESC LIMIT 5;
-- SELECT id, user_id, account_type, currency, available, frozen, version
-- FROM `account` WHERE id IN (900001, 900101);