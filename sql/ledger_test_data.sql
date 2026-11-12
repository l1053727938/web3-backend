-- ============================================================================
-- 复式账本联调测试数据（第 2 周 Day 5-6）
--
-- 用途：为 LedgerController 的 deposit / freeze / settle / unfreeze / reverse
--       提供固定 account.id，脚本与 HTTP 示例可直接引用。
--
-- 用法：
--   mysql -u root -p < sql/ledger_test_data.sql
--
-- 特性：
--   * account.id 固定，重复执行会把测试账户余额重置为初始值（version 归零）；
--   * 不清理 ledger_entry / ledger_posting（账本只增不改）。
--     若要从零对账，先手工执行文件末尾的可选清理 SQL（仅限本地开发库）。
--   * 这些 ID 预留给本地联调，不要在生产库执行本脚本。
-- ============================================================================

USE web3_backend;

INSERT INTO `account` (`id`, `user_id`, `account_type`, `currency`, `available`, `frozen`, `version`)
VALUES
    -- 用户侧账户（SPOT）
    (900001, 10001, 'SPOT', 'USDT', 1000, 0, 0),   -- 用户A / Maker：初始 1000 USDT
    (900002, 10002, 'SPOT', 'USDT', 1000, 0, 0),   -- 用户B / Taker：初始 1000 USDT
    (900003, 10003, 'SPOT', 'USDT', 500,  0, 0),   -- 用户C / 手续费付款方：初始 500 USDT
    (900004, 10001, 'SPOT', 'ETH',  10,   0, 0),   -- 用户A 的 ETH 账户（币种不匹配测试）

    -- 平台侧账户（PLATFORM）：允许负余额
    (900101, 900000001, 'PLATFORM', 'USDT', 0, 0, 0), -- 平台代收负债（充值落账的对手方）
    (900102, 900000002, 'PLATFORM', 'USDT', 0, 0, 0), -- 平台手续费收入
    (900103, 900000001, 'PLATFORM', 'ETH',  0, 0, 0)  -- 平台 ETH 负债（备用）
AS new
ON DUPLICATE KEY UPDATE
    `available` = new.`available`,
    `frozen`    = new.`frozen`,
    `version`   = 0;

-- ----------------------------------------------------------------------------
-- 账户清单（与 scripts/ledger-api-demo.sh / .http 中的常量保持一致）
--
--   account.id | user_id   | 类型     | 币种 | 初始可用 | 用途
--   -----------+-----------+----------+------+----------+----------------------
--   900001     | 10001     | SPOT     | USDT | 1000     | 用户A（Maker）
--   900002     | 10002     | SPOT     | USDT | 1000     | 用户B（Taker）
--   900003     | 10003     | SPOT     | USDT | 500      | 用户C（手续费付款方）
--   900004     | 10001     | SPOT     | ETH  | 10       | 币种不匹配测试
--   900101     | 900000001 | PLATFORM | USDT | 0        | 平台代收负债
--   900102     | 900000002 | PLATFORM | USDT | 0        | 平台手续费收入
--   900103     | 900000001 | PLATFORM | ETH  | 0        | 平台 ETH 负债
--
-- 典型调用顺序（详见 scripts/ledger-api-demo.sh）：
--   1. deposit    用户A +100 / 平台代收负债 -100      （注入资金，平台负债变负）
--   2. deposit    用户B +200、用户C +50
--   3. freeze     用户B 冻结 150（available -> frozen）
--   4. settle     Taker(B) frozen -100 / Maker(A) available +100；
--                 C available -1 / 平台手续费 +1
--   5. unfreeze   用户B 解冻 30（frozen -> available）
--   6. reverse    冲正第 1 步的充值：用户A -100 / 平台代收负债 +100
--
-- 执行完 demo 后的期望余额：
--   900001 用户A       available=1100  frozen=0
--   900002 用户B       available=1080  frozen=20
--   900003 用户C       available=549   frozen=0
--   900101 平台代收负债 available=-250  frozen=0
--   900102 平台手续费   available=1     frozen=0
-- ----------------------------------------------------------------------------

-- 查看测试账户余额：
-- SELECT id, user_id, account_type, currency, available, frozen, version
-- FROM `account`
-- WHERE id IN (900001, 900002, 900003, 900004, 900101, 900102, 900103)
-- ORDER BY id;

-- 查看最近写入的分录组与明细：
-- SELECT e.id, e.entry_no, e.biz_type, e.biz_no, e.reverse_of, e.created_at,
--        p.account_id, p.direction, p.balance_type, p.amount, p.balance_after
-- FROM ledger_entry e
-- JOIN ledger_posting p ON p.entry_id = e.id
-- ORDER BY e.id DESC, p.id
-- LIMIT 40;

-- ----------------------------------------------------------------------------
-- 可选：清空账本历史，回到“账户余额与分录完全一致”的干净状态。
-- ⚠ 仅限本地开发库；生产环境账本两表只允许 INSERT，禁止删除。
-- ----------------------------------------------------------------------------
-- DELETE FROM ledger_posting;
-- DELETE FROM ledger_entry;
-- ALTER TABLE ledger_entry AUTO_INCREMENT = 1;
-- ALTER TABLE ledger_posting AUTO_INCREMENT = 1;