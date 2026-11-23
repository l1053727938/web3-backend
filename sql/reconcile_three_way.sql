-- ============================================================================
-- 第 4 周 Day 5-6：对账三方模型（只读查询）
--
-- 三方口径：
--   1) 链上事实：transfer_event_log（全量 Transfer，必须先过滤平台收款地址）
--   2) 账本    ：ledger_entry（业务键 biz_no）+ ledger_posting（金额/账户腿）
--   3) 余额    ：account（账本的物化视图）
--   桥表      ：deposit_record（链上键 -> 业务单号 deposit_no）
--
-- 实际关联路径（计划里的 biz_no = chain_id+tx_hash+log_index 是概念键，不是物理列）：
--   transfer_event_log
--     -- (chain_id, transaction_hash, log_index)
--   deposit_record
--     -- (deposit_no)
--   ledger_entry (biz_type='DEPOSIT', biz_no=deposit_no)
--     -- (entry_id)
--   ledger_posting (direction='CREDIT', balance_type='AVAILABLE')
--     -- (account_id)
--   account
--
-- 用法：
--   1. 修改下面 5 个参数；
--   2. mysql -u root -p web3_backend < sql/reconcile_three_way.sql
--   3. 所有查询只读；脚本只创建会话级 TEMPORARY TABLE，不写入业务表。
--
-- 边界：本脚本只做“模型 + 关联 SQL + 差异查询”。
--   ReconcileJob、reconcile_diff 表、六类差异、三类报表、分片/从库、INIT 接管、
--   Reorg 演练均属第 5 周，不在本脚本范围。
-- ============================================================================

SET @chain_id = 31337;                    -- Anvil 本地链；主网 1，Sepolia 11155111
SET @contract = '0x5fbdb2315678afecb367f032d93f642f64180aa3';
SET @from_block = 0;
SET @to_block = 999999999;
SET @platform_liability_account_id = 900101;  -- web3.deposit.consumer.platform-liability-account-id

-- ----------------------------------------------------------------------------
-- 0. 链上侧完整性自检（库内计数）
--
-- 注意：这是 transfer_event_log 的库内计数，不是链上计数。
-- 链上条数用 GET /api/chain/logs/transfer/{contract}?fromBlock&toBlock 的 data 长度；
-- 二者对齐后，再看 deposit_record / ledger 的差异（见文档第三节）。
-- ----------------------------------------------------------------------------
SELECT COUNT(*) AS db_transfer_count
FROM transfer_event_log
WHERE chain_id = @chain_id
  AND contract_address = @contract
  AND block_number BETWEEN @from_block AND @to_block;

-- ----------------------------------------------------------------------------
-- 1. 平台收款地址集合
--
-- transfer_event_log 存的是合约的【全部】Transfer，必须过滤到平台地址，
-- 否则用户之间的普通转账会被误报成“链上有、账本无”。
--
-- deposit_wallet 刻意不过滤 status：status=0 的停用地址在历史上仍是平台地址，
-- 历史对账必须纳入；当前路由是否启用由业务侧另行判断。
-- 配置中心热钱包（application.yml web3.deposit.hot-wallets）可能不在表里，
-- 这里用 UNION ALL + NOT EXISTS 补一个示例地址，按你本地配置增删。
-- ----------------------------------------------------------------------------
DROP TEMPORARY TABLE IF EXISTS reconcile_platform_wallet;
CREATE TEMPORARY TABLE reconcile_platform_wallet AS
SELECT chain_id, address, contract, wallet_type, user_id, status
FROM deposit_wallet
WHERE chain_id = @chain_id
UNION ALL
SELECT @chain_id,
       '0x70997970c51812dc3a010c7d01b50e0d17dc79c8',
       NULL,
       'HOT',
       NULL,
       1
WHERE NOT EXISTS (
    SELECT 1
    FROM deposit_wallet
    WHERE chain_id = @chain_id
      AND address = '0x70997970c51812dc3a010c7d01b50e0d17dc79c8'
);

-- ----------------------------------------------------------------------------
-- 2. 三方关联明细（物化到会话临时表）
--
-- 只取 DEPOSIT 的用户侧 AVAILABLE/CREDIT 一条腿作为账本金额；
-- 不要用整组 posting 求和，否则会把平台负债腿也算进去（金额翻倍）。
-- ----------------------------------------------------------------------------
DROP TEMPORARY TABLE IF EXISTS reconcile_chain_deposit;
CREATE TEMPORARY TABLE reconcile_chain_deposit AS
SELECT
    tel.chain_id,
    tel.contract_address,
    tel.block_number,
    tel.transaction_hash,
    tel.log_index,
    tel.from_address,
    tel.to_address,
    tel.raw_value,
    tel.display_value,
    tel.decimals,
    pw.wallet_type   AS cfg_wallet_type,
    pw.user_id       AS cfg_user_id,
    pw.status        AS cfg_wallet_status,
    dr.deposit_no,
    dr.wallet_type,
    dr.user_id,
    dr.currency,
    dr.status        AS deposit_status,
    dr.notify_status,
    le.id            AS entry_id,
    le.entry_no,
    le.status        AS entry_status,
    lp.id            AS posting_id,
    lp.account_id,
    lp.amount        AS ledger_amount,
    lp.currency      AS ledger_currency
FROM transfer_event_log tel
JOIN reconcile_platform_wallet pw
  ON pw.chain_id = tel.chain_id
 AND pw.address = tel.to_address
 AND (pw.contract IS NULL OR pw.contract = tel.contract_address)
LEFT JOIN deposit_record dr
  ON dr.chain_id = tel.chain_id
 AND dr.transaction_hash = tel.transaction_hash
 AND dr.log_index = tel.log_index
LEFT JOIN ledger_entry le
  ON le.biz_type = 'DEPOSIT'
 AND le.biz_no = dr.deposit_no
LEFT JOIN ledger_posting lp
  ON lp.entry_id = le.id
 AND lp.direction = 'CREDIT'
 AND lp.balance_type = 'AVAILABLE'
WHERE tel.chain_id = @chain_id
  AND tel.contract_address = @contract
  AND tel.block_number BETWEEN @from_block AND @to_block;

-- 2.1 关联结果抽样（确认 join 路径通）
SELECT *
FROM reconcile_chain_deposit
ORDER BY block_number, log_index
LIMIT 100;

-- ----------------------------------------------------------------------------
-- 3. 链上 vs 账本：差异分类
--
-- CHAIN_WITHOUT_DEPOSIT  ：链上有平台地址转账，但 deposit_record 没生成
--                          -> 路由失败/漏扫/地址配置问题；补拉重放可补齐
-- DEPOSIT_WITHOUT_LEDGER ：deposit_record 有，但账本没有 DEPOSIT 组
--                          -> Outbox/消费者/账本失败；查 outbox_message / consumer_biz / DLQ
-- LEDGER_WITHOUT_USER_LEG: 账本组存在，但没有用户侧 CREDIT/AVAILABLE 腿
--                          -> 账本数据异常
-- AMOUNT_MISMATCH        ：链上 display_value 与账本 amount 不一致
--                          -> 优先看 decimals > 18 的精度差（第 5 周专项）
--
-- HOT 充值没有 userId，消费者按设计拒绝入用户账本；
-- 因此 HOT 的 DEPOSIT_WITHOUT_LEDGER 不算事故，这里不报。
-- 不要过滤 deposit_record.status='CREDITED'：当前消费者在 PENDING_CONFIRM 阶段就入账。
-- ----------------------------------------------------------------------------
SELECT *
FROM (
    SELECT
        cd.chain_id,
        cd.transaction_hash,
        cd.log_index,
        cd.block_number,
        cd.to_address,
        cd.display_value,
        cd.deposit_no,
        COALESCE(cd.wallet_type, cd.cfg_wallet_type) AS wallet_type,
        cd.deposit_status,
        cd.notify_status,
        cd.entry_id,
        cd.entry_no,
        cd.ledger_amount,
        CASE
            WHEN cd.deposit_no IS NULL THEN 'CHAIN_WITHOUT_DEPOSIT'
            WHEN cd.entry_id IS NULL
                 AND COALESCE(cd.wallet_type, cd.cfg_wallet_type) = 'USER'
                THEN 'DEPOSIT_WITHOUT_LEDGER'
            WHEN cd.entry_id IS NOT NULL AND cd.posting_id IS NULL
                THEN 'LEDGER_WITHOUT_USER_LEG'
            WHEN cd.posting_id IS NOT NULL
                 AND cd.ledger_amount <> CAST(cd.display_value AS DECIMAL(65, 18))
                THEN 'AMOUNT_MISMATCH'
            ELSE 'OK'
        END AS diff_type
    FROM reconcile_chain_deposit cd
) d
WHERE d.diff_type <> 'OK'
ORDER BY d.block_number, d.log_index;

-- ----------------------------------------------------------------------------
-- 4. 账本有、链上无：DEPOSIT 组没有对应 deposit_record
--
-- 常见原因：人工调用 /api/ledger/deposit、历史数据迁移、或数据损坏。
-- ----------------------------------------------------------------------------
SELECT le.id, le.entry_no, le.biz_no, le.status, le.created_at
FROM ledger_entry le
LEFT JOIN deposit_record dr ON dr.deposit_no = le.biz_no
WHERE le.biz_type = 'DEPOSIT'
  AND dr.deposit_no IS NULL
ORDER BY le.id;

-- ----------------------------------------------------------------------------
-- 5. 冲正识别：原分录状态不会变成 REVERSED，必须靠 reverse_of 关联
-- ----------------------------------------------------------------------------
SELECT le.id,
       le.entry_no,
       le.biz_no,
       rev.id        AS reverse_entry_id,
       rev.entry_no  AS reverse_entry_no,
       rev.created_at AS reversed_at
FROM ledger_entry le
JOIN ledger_entry rev
  ON rev.biz_type = 'REVERSE'
 AND rev.reverse_of = le.id
WHERE le.biz_type = 'DEPOSIT'
ORDER BY le.id;

-- ----------------------------------------------------------------------------
-- 6. 账本 vs 余额：用最近一条 posting 的 balance_after 对比物化余额
--
-- 这是推荐口径：它能适配有期初余额的账户（如 sql/ledger_test_data.sql 的种子账户）。
-- NO_AVAILABLE_POSTING / NO_FROZEN_POSTING 表示账户有余额但没有任何 posting，
-- 通常是期初余额（种子数据），不是账本 bug；对账时应单独归类或排除。
-- ----------------------------------------------------------------------------
SELECT a.id,
       a.user_id,
       a.account_type,
       a.currency,
       a.available,
       a.frozen,
       pa.balance_after AS last_available_after,
       pf.balance_after AS last_frozen_after,
       CASE
           WHEN pa.id IS NULL AND a.available <> 0 THEN 'NO_AVAILABLE_POSTING'
           WHEN pf.id IS NULL AND a.frozen <> 0 THEN 'NO_FROZEN_POSTING'
           WHEN pa.id IS NOT NULL AND a.available <> pa.balance_after THEN 'AVAILABLE_MISMATCH'
           WHEN pf.id IS NOT NULL AND a.frozen <> pf.balance_after THEN 'FROZEN_MISMATCH'
       END AS diff_type
FROM account a
LEFT JOIN ledger_posting pa ON pa.id = (
    SELECT p.id
    FROM ledger_posting p
    WHERE p.account_id = a.id
      AND p.balance_type = 'AVAILABLE'
    ORDER BY p.id DESC
    LIMIT 1
)
LEFT JOIN ledger_posting pf ON pf.id = (
    SELECT p.id
    FROM ledger_posting p
    WHERE p.account_id = a.id
      AND p.balance_type = 'FROZEN'
    ORDER BY p.id DESC
    LIMIT 1
)
WHERE (pa.id IS NULL AND a.available <> 0)
   OR (pf.id IS NULL AND a.frozen <> 0)
   OR (pa.id IS NOT NULL AND a.available <> pa.balance_after)
   OR (pf.id IS NOT NULL AND a.frozen <> pf.balance_after)
ORDER BY a.id;

-- ----------------------------------------------------------------------------
-- 7. 账本 vs 余额：从 0 累加 posting 的严格重建
--
-- 仅适用于“生命周期完全在账本里”的账户；种子账户/期初余额会误报。
-- 若数据源只包含账本产生的账户，可用这条做最严格校验。
-- ----------------------------------------------------------------------------
SELECT a.id,
       a.user_id,
       a.account_type,
       a.currency,
       a.available,
       a.frozen,
       COALESCE(SUM(CASE
           WHEN p.balance_type = 'AVAILABLE' AND p.direction = 'CREDIT' THEN p.amount
           WHEN p.balance_type = 'AVAILABLE' AND p.direction = 'DEBIT' THEN -p.amount
           ELSE 0
       END), 0) AS ledger_available,
       COALESCE(SUM(CASE
           WHEN p.balance_type = 'FROZEN' AND p.direction = 'CREDIT' THEN p.amount
           WHEN p.balance_type = 'FROZEN' AND p.direction = 'DEBIT' THEN -p.amount
           ELSE 0
       END), 0) AS ledger_frozen
FROM account a
LEFT JOIN ledger_posting p ON p.account_id = a.id
GROUP BY a.id, a.user_id, a.account_type, a.currency, a.available, a.frozen
HAVING a.available <> ledger_available
    OR a.frozen <> ledger_frozen
ORDER BY a.id;

-- ----------------------------------------------------------------------------
-- 8. 三方之“总额 vs 平台负债”：DEPOSIT 专项试算平衡
--
-- 每笔充值：用户 AVAILABLE CREDIT（+金额）/ 平台代收负债 AVAILABLE DEBIT（-金额）。
-- 同一币种下，两边带符号相加应为 0；delta <> 0 即可能是漏记腿或金额不一致。
-- 平台侧只认配置的负债账户 ID，避免把手续费收入/待结算账户混进来。
-- ----------------------------------------------------------------------------
SELECT p.currency,
       SUM(CASE
           WHEN a.id = @platform_liability_account_id AND p.direction = 'DEBIT' THEN p.amount
           ELSE 0
       END) AS platform_debit,
       SUM(CASE
           WHEN a.account_type <> 'PLATFORM' AND p.direction = 'CREDIT' THEN p.amount
           ELSE 0
       END) AS user_credit,
       SUM(CASE
           WHEN a.id = @platform_liability_account_id AND p.direction = 'DEBIT' THEN p.amount
           WHEN a.account_type <> 'PLATFORM' AND p.direction = 'CREDIT' THEN -p.amount
           ELSE 0
       END) AS delta
FROM ledger_posting p
JOIN ledger_entry e ON e.id = p.entry_id
JOIN account a ON a.id = p.account_id
WHERE e.biz_type = 'DEPOSIT'
  AND p.balance_type = 'AVAILABLE'
GROUP BY p.currency
HAVING delta <> 0;

-- ----------------------------------------------------------------------------
-- 9. 总额 vs 平台负债：账户口径试算（诊断用）
--
-- 平台侧合计应等于用户侧合计的相反数（负债在资产口径下为负）。
-- 种子账户/未入账的期初余额会打破这个恒等式，先排除再判定资损。
-- ----------------------------------------------------------------------------
SELECT currency,
       SUM(CASE WHEN account_type = 'PLATFORM' THEN available + frozen ELSE 0 END) AS platform_total,
       SUM(CASE WHEN account_type <> 'PLATFORM' THEN available + frozen ELSE 0 END) AS user_total,
       SUM(CASE WHEN account_type = 'PLATFORM' THEN available + frozen
                ELSE -available - frozen END) AS delta
FROM account
GROUP BY currency
ORDER BY currency;

-- ----------------------------------------------------------------------------
-- 清理（可选）：会话结束自动消失；显式清理便于同一会话重复执行
-- ----------------------------------------------------------------------------
-- DROP TEMPORARY TABLE IF EXISTS reconcile_chain_deposit;
-- DROP TEMPORARY TABLE IF EXISTS reconcile_platform_wallet;