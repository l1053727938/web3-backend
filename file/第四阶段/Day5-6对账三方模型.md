# Day 5-6：对账三方模型

> 对应 18 周计划第 4 周 Day 5-6
> 目标：把链上事实、账本分录、账户余额按统一关联路径打通，产出三条核对线与差异查询
> 产出：`sql/reconcile_three_way.sql`（关联 SQL + 差异查询 + 验收 SQL）与本文档
> 边界：只做模型/SQL，不实现 `ReconcileJob`、`reconcile_diff` 表、六类报表、分片/从库、`INIT` 接管、Reorg 演练（全部留到第 5 周）

---

## 一、先把三方口径定死

### 1.1 三方不是三张平级的表

| 层 | 表 | 角色 | 关键键 |
|---|---|---|---|
| 链上事实 | `transfer_event_log` | 链上发生了什么（全量 Transfer） | `(chain_id, transaction_hash, log_index)` |
| 桥 | `deposit_record` | 把链上事件映射为平台充值单 | 同上 + `deposit_no` |
| 账本 | `ledger_entry` + `ledger_posting` | 平台记了什么账 | `(biz_type, biz_no)` / `entry_id` |
| 余额 | `account` | 账本的物化视图 | `(user_id, account_type, currency)` |

计划原文把账本侧写成 `ledger_posting`，但**`biz_no` 在 `ledger_entry` 上**，
`ledger_posting` 只有 `entry_id` 和金额。因此对账必须把账本侧定义为
`ledger_entry + ledger_posting`，不能只查 `ledger_posting`。

### 1.2 对账只读，不改账

本 Day 只做查询和差异定位，不自动冲正、不自动补账。差异的处置（补单、冲正、人工确认）
留给第 5 周的差异分类和人工流程。

---

## 二、关联键：不是一个 `biz_no`，而是两段映射

### 2.1 计划口径 vs 实现口径

计划写的是：

```text
biz_no = chain_id + tx_hash + log_index
```

这是**概念上的对账键**，不是数据库里的物理列。实际实现分两段：

```text
链上键 chain_key = (chain_id, transaction_hash, log_index)
        │
        ▼  deposit_record.uk_chain_tx_log
业务键 biz_no = deposit_no
        │
        ▼  ledger_entry.uk_biz (biz_type='DEPOSIT', biz_no=deposit_no)
账本组 ledger_entry
        │
        ▼  ledger_posting.entry_id
账本明细（用户侧 AVAILABLE / CREDIT 一条腿）
        │
        ▼  account.id
余额
```

`deposit_no` 是 `DP + MMddHHmmssSSS + 16 位十六进制随机`（`DepositRoutingServiceImpl`），
**没有编码链上键**；链上键与业务键的桥接只能靠 `deposit_record`。

### 2.2 为什么 `ledger_posting` 不能单独作为一方

- `ledger_posting` 没有 `biz_no`，只有 `entry_id`；
- `ledger_posting` 一笔业务至少两条腿（用户侧 + 平台侧），直接求和会把金额翻倍；
- 冲正靠 `ledger_entry.reverse_of`，不是 `ledger_posting` 上的字段。

正确做法：先按 `ledger_entry.biz_type='DEPOSIT' AND biz_no=deposit_no` 找到账本组，
再只取用户侧 `direction='CREDIT' AND balance_type='AVAILABLE'` 的一条腿作为账本金额。

---

## 三、链上侧完整性自检：`saved-count`

### 3.1 先证明链上事件没漏，再谈账本差异

`GET /api/chain/logs/transfer/saved-count/{contract}` 统计的是
`transfer_event_log` **已入库条数**，不是链上条数。链上独立直查要用日志列表端点：

```bash
CONTRACT=0x5fbdb2315678afecb367f032d93f642f64180aa3
FROM=0
TO=999999999

# 链上条数：日志列表的 data 长度
curl -s "http://localhost:8080/api/chain/logs/transfer/$CONTRACT?fromBlock=$FROM&toBlock=$TO" \
  | jq '.data | length'

# 库内条数
curl -s "http://localhost:8080/api/chain/logs/transfer/saved-count/$CONTRACT?fromBlock=$FROM&toBlock=$TO" \
  | jq '.data'

# 补拉游标
curl -s "http://localhost:8080/api/chain/logs/cursor/$CONTRACT" | jq '.data'

# 发现缺口后补拉（幂等；纯重放 savedCount=0）
curl -s -X POST \
  "http://localhost:8080/api/chain/logs/transfer/backfill/$CONTRACT?fromBlock=$FROM&toBlock=$TO" | jq
```

没有 `jq` 时用其他 JSON 工具或肉眼数 `data` 数组长度。两者对齐后，说明
`transfer_event_log` 没漏；后再用第四节 SQL 检查 `deposit_record` / 账本。

### 3.2 `saved-count` 的边界

- 它只证明“流水入库”，**不证明路由/入账**；`deposit_record` 或 `ledger_entry` 仍可能缺。
- `BackfillResultVO.savedCount` 是“本次新入库数”，重放时为 0，不能当作区间总条数。
- 链上事件与账本的时间窗口不同：链上用 `block_number`，账本用 `created_at`，
  本 Day 的关联 SQL 统一用链上区块窗口，账本按 `deposit_no` 关联，不按时间切。

---

## 四、三条核对线

完整可执行脚本：`sql/reconcile_three_way.sql`。执行前修改文件顶部的
`@chain_id / @contract / @from_block / @to_block / @platform_liability_account_id`；
`@contract` 用小写（`transfer_event_log.contract_address` 按小写存储）。
脚本只创建会话级 `TEMPORARY TABLE`，不写业务表。

### 4.1 线一：链上 vs 账本（漏账/重复）

**先过滤平台收款地址**：`transfer_event_log` 存的是合约全部 Transfer，
用户之间的普通转账也会在里面。过滤来源：

1. `deposit_wallet` 表：`chain_id + address`，且 `contract IS NULL OR contract = tel.contract_address`；
2. 配置中心 `web3.deposit.hot-wallets`（`application.yml`）——可能不在表里，需要手工补进 CTE。

历史对账必须包含 `deposit_wallet.status=0` 的停用地址：它历史上仍是平台地址。
当前路由只加载启用地址，但历史转账仍应算充值。

脚本的核心关联路径：

```sql
transfer_event_log tel
  JOIN deposit_wallet pw
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
```

差异分类：

| `diff_type` | 含义 | 处理方向 |
|---|---|---|
| `CHAIN_WITHOUT_DEPOSIT` | 链上有平台地址转账，但 `deposit_record` 没生成 | 路由失败/漏扫/地址配置问题；补拉重放可补齐 |
| `DEPOSIT_WITHOUT_LEDGER` | `deposit_record` 有，但账本没有 `DEPOSIT` 组 | 查 `outbox_message` / `consumer_biz` / `dlq_message`；消费者重试或人工补偿 |
| `LEDGER_WITHOUT_USER_LEG` | 账本组存在，但没有用户侧 `CREDIT/AVAILABLE` 腿 | 账本数据异常，需人工核查 |
| `AMOUNT_MISMATCH` | 链上 `display_value` 与账本 `amount` 不一致 | 先看 `decimals > 18` 的精度差（第 5 周专项） |

**HOT 充值没有 `userId`**，消费者按设计拒绝入用户账本；HOT 的
`DEPOSIT_WITHOUT_LEDGER` 不算事故，脚本不报。USER 充值才要求账本存在。

**不要过滤 `deposit_record.status='CREDITED'`**：当前消费者在 `PENDING_CONFIRM`
阶段就入账，`CREDITED/INVALID` 尚未实现；加了过滤会把所有充值误报为“链上有、账本无”。

**冲正**：`ledger_entry.status` 不会变成 `REVERSED`，必须用
`ledger_entry rev ON rev.biz_type='REVERSE' AND rev.reverse_of=le.id` 识别；
脚本第 5 节提供了冲正查询。

**重复**：`deposit_record.uk_chain_tx_log` 和 `ledger_entry.uk_biz` 已经防重复组；
`ledger_posting` 没有唯一约束，脚本的 `LEDGER_WITHOUT_USER_LEG` / 多行结果可提示重复腿。

### 4.2 线二：账本 vs 余额（账本自身一致性）

推荐口径：**用账户最近一条 posting 的 `balance_after` 对比 `account.available/frozen`**。
它能适配有期初余额的账户（如 `sql/ledger_test_data.sql` 的种子账户）。

```sql
-- 完整 SQL 见 reconcile_three_way.sql 第 6 节
SELECT a.id, a.available, a.frozen,
       pa.balance_after AS last_available_after,
       pf.balance_after AS last_frozen_after
FROM account a
LEFT JOIN ledger_posting pa ON pa.id = (
    SELECT p.id FROM ledger_posting p
    WHERE p.account_id = a.id AND p.balance_type = 'AVAILABLE'
    ORDER BY p.id DESC LIMIT 1
)
LEFT JOIN ledger_posting pf ON pf.id = (
    SELECT p.id FROM ledger_posting p
    WHERE p.account_id = a.id AND p.balance_type = 'FROZEN'
    ORDER BY p.id DESC LIMIT 1
)
WHERE ...;
```

严格重建（从 0 累加 posting）：

```text
ledger_available = SUM(CASE direction WHEN 'CREDIT' THEN amount ELSE -amount END)
ledger_frozen    = 同理
```

只适用于“生命周期完全在账本里”的账户。`ledger_test_data.sql` 的 900001/900002 等
种子账户有**开账余额但没有开账分录**，严格重建会误报；此时应使用 `balance_after`
口径，或显式排除种子账户。

`account.total` 是数据库生成列（`available + frozen`），对账不需要单独比较。

### 4.3 线三：总额 vs 平台负债（资损）

**DEPOSIT 专项试算平衡**：每笔充值用户 `AVAILABLE CREDIT`，平台代收负债
`AVAILABLE DEBIT`；同一币种两边带符号相加应为 0。

```sql
-- 完整 SQL 见 reconcile_three_way.sql 第 8 节
SELECT p.currency,
       SUM(CASE WHEN a.id = @platform_liability_account_id
                 AND p.direction = 'DEBIT' THEN p.amount ELSE 0 END) AS platform_debit,
       SUM(CASE WHEN a.account_type <> 'PLATFORM'
                 AND p.direction = 'CREDIT' THEN p.amount ELSE 0 END) AS user_credit,
       SUM(CASE WHEN a.id = @platform_liability_account_id
                 AND p.direction = 'DEBIT' THEN p.amount
                WHEN a.account_type <> 'PLATFORM'
                 AND p.direction = 'CREDIT' THEN -p.amount
                ELSE 0 END) AS delta
FROM ledger_posting p
JOIN ledger_entry e ON e.id = p.entry_id
JOIN account a ON a.id = p.account_id
WHERE e.biz_type = 'DEPOSIT' AND p.balance_type = 'AVAILABLE'
GROUP BY p.currency
HAVING delta <> 0;
```

预期返回空集。平台侧只认配置的负债账户
（`web3.deposit.consumer.platform-liability-account-id`），
不要用“所有 PLATFORM 账户”代替，否则手续费收入/待结算会混入。

账户口径试算（诊断用）：

```sql
SELECT currency,
       SUM(CASE WHEN account_type = 'PLATFORM' THEN available + frozen ELSE 0 END) AS platform_total,
       SUM(CASE WHEN account_type <> 'PLATFORM' THEN available + frozen ELSE 0 END) AS user_total,
       SUM(CASE WHEN account_type = 'PLATFORM' THEN available + frozen
                ELSE -available - frozen END) AS delta
FROM account
GROUP BY currency;
```

平台侧合计应为用户侧合计的相反数（负债在资产口径下为负）。种子账户/未入账的期初
余额会打破恒等式，先排除再判定资损。

---

## 五、乱序与版本

### 5.1 当前已有的顺序保障

- `outbox_message.partition_key = userId`（USER 充值），同一用户事件进同一 Kafka partition；
  HOT 无 `userId` 时回退 `depositNo`。
- `deposit_record.uk_chain_tx_log` + `INSERT IGNORE` 防重复行；
- `ledger_entry.uk_biz(DEPOSIT, deposit_no)` 防重复入账。

### 5.2 `biz_version` 现状：未实现

计划提到的“业务版本号（`biz_version`），收到比当前小的版本直接丢弃”在当前
schema/代码中**不存在**。本篇只记录口径，不实现该字段。

### 5.3 乱序影响的是状态推进，不是加法记账

- 纯充值加法可交换，重复由唯一键挡住，乱序不会改变最终余额；
- 冻结/扣减/结算等非交换操作、以及状态推进（如 `PENDING_CONFIRM → CREDITED`）
  需要版本/条件更新；
- 第 5 周补 `biz_version` 时，也应只用于状态守卫，**不能因此丢弃财务 posting**。

---

## 六、验收 SQL 与预期

### 6.1 执行完整脚本

```bash
mysql -u root -p --default-character-set=utf8mb4 web3_backend < sql/reconcile_three_way.sql
```

脚本会依次输出：

1. `db_transfer_count`：库内 Transfer 条数（与链上日志列表长度对齐）；
2. 关联明细抽样 `LIMIT 100`；
3. 链上 vs 账本差异（应逐步收敛为空）；
4. 账本有、链上无；
5. 冲正列表；
6. 账本 vs 余额（`balance_after` 口径）；
7. 严格重建（种子账户会误报，按需排除）；
8. DEPOSIT 试算平衡（应为空）；
9. 账户口径试算（诊断）。

### 6.2 人工构造缺口

1. 停消费者/切 `WEB3_OUTBOX_TRANSPORT=redis`，触发一笔 USER 充值：
   `deposit_record` 有、`consumer_biz` 无、账本无 → 应命中 `DEPOSIT_WITHOUT_LEDGER`；
2. 恢复消费者或重放 Outbox → 查询应收敛为空；
3. 回退补拉游标重放同一区间：`savedCount=0`，差异不增加；
4. 手工往 `transfer_event_log` 插入一条平台地址事件但不生成 `deposit_record`
   → 应命中 `CHAIN_WITHOUT_DEPOSIT`。

### 6.3 预期证据

- `CHAIN_WITHOUT_DEPOSIT`：链上有平台地址转账但 `deposit_record` 缺失；
- `DEPOSIT_WITHOUT_LEDGER`：`deposit_record` 有但账本无；查 Outbox 链路；
- `AMOUNT_MISMATCH`：链上 `display_value` 与账本 `amount` 不一致；
- `AVAILABLE_MISMATCH` / `FROZEN_MISMATCH`：账本与余额不一致；
- DEPOSIT 试算 `delta <> 0`：平台负债腿缺失或金额不一致。

> 本地验证（2026-11-23）：在临时 MySQL 8.0 上构造 5 条事件（完整链路 / 链上无 deposit / deposit 无 ledger / 金额不一致 / 缺用户腿），
> 脚本可执行，差异查询命中上述 4 类；账本 vs 余额、DEPOSIT 试算也按预期返回非空差异。

---

## 七、验收对照

| 计划要求 | 状态 | 证据 |
|---|---|---|
| 三方数据源口径 | ✅ | 本文档第一节表；`transfer_event_log` / `deposit_record` / `ledger_entry+posting` / `account` |
| 关联 SQL | ✅ | `sql/reconcile_three_way.sql` 第 2 节（临时表）；临时 MySQL 8.0 实跑通过 |
| 一条“链上有、账本无”查询 | ✅ | 同脚本第 3 节（`CHAIN_WITHOUT_DEPOSIT` / `DEPOSIT_WITHOUT_LEDGER`）；构造数据命中 |
| 链上侧 saved-count 自检 | ✅ | 本文档第三节 curl 四连；`saved-count` 与日志列表长度对齐 |
| 三条核对线 | ✅ | 第 3、6、8 节 SQL：链上 vs 账本 / 账本 vs 余额 / 总额 vs 平台负债 |
| 乱序处理 | 🟡 部分 | 分区键已实现；`biz_version` 未实现（第 5 周） |
| ReconcileJob / `reconcile_diff` / 六类 / 分片 / 从库 / INIT / Reorg | ⬜ 第 5 周 | 本 Day 只做模型与 SQL |
| RPC 主备 `RpcProviderManager` | ⬜ 未实现 | 计划 Day 5-6 的“本周余量”附加项，与三方对账正交 |

---

## 八、避坑与已知限制

1. **`biz_no` 不是链上复合键**：必须先经 `deposit_record` 桥接；
   `deposit_no` 是随机业务单号。
2. **`ledger_posting` 没有 `biz_no`**：必须 join `ledger_entry`，且只取用户侧一条腿。
3. **`transfer_event_log` 是全量 Transfer**：必须按 `deposit_wallet` + 配置热钱包过滤。
4. **配置热钱包可能不在 `deposit_wallet` 表里**：脚本用 `UNION ALL` 示例补充，
   按你本地 `web3.deposit.hot-wallets` 增删。
5. **历史对账要包含停用地址**：`deposit_wallet.status=0` 的历史转账仍算充值。
6. **HOT 充值没有 `userId`**：消费者按设计拒绝入用户账本，单独归类，不算事故。
7. **不能过滤 `status='CREDITED'`**：当前消费者在 `PENDING_CONFIRM` 阶段就入账。
8. **不能靠 `status='REVERSED'` 识别冲正**：用 `reverse_of` / `REVERSE` 分录。
9. **种子账户有期初余额**：严格重建会误报；优先用 `balance_after` 口径或排除种子账户。
10. **`display_value` 是字符串、`ledger.amount` 是 DECIMAL**：比较要 cast；
    链上精度 >18 位时存在预期精度差（第 5 周专项）。
11. **`saved-count` 是库内计数**，不是链上计数；链上条数用日志列表的 `data` 长度。
12. **多链必须带 `chain_id`**：所有查询都以 `chain_id` 为第一过滤条件。
13. **Reorg/INVALID/时间差/六类报表**：第 5 周，不在本 Day。
14. **RPC 主备 failover**：计划中的附加项，尚未实现；与三方对账独立。

---

## 九、与第 5 周的边界

| 维度 | 本 Day（第 4 周 Day 5-6） | 第 5 周完整对账 |
|---|---|---|
| 目标 | 三方模型打通、口径定死 | 真跑差异报告、差错处置 |
| 产出 | `sql/reconcile_three_way.sql` + 本文档 | `ReconcileJob` + `reconcile_diff` |
| 差异分类 | 4 类：CHAIN_WITHOUT_DEPOSIT / DEPOSIT_WITHOUT_LEDGER / LEDGER_WITHOUT_USER_LEG / AMOUNT_MISMATCH | 六类：时间差/遗漏/重复/重组回滚/未知代币/精度差 |
| 报表 | 不做 | 三类：在途/长短款/待定性 |
| 调度/分片/从库 | 手工 SQL | 按日窗口 + 8 分片 + 独立线程池 + 50 QPS + 只读从库 |
| INIT/Reorg | 不涉及 | INIT 三态 + 接管 Job + Reorg 演练 |
| 精度差 | 保留 `decimals` 快照，不构造案例 | 必须构造一条真实精度差 |
| 数据源 | 主库只读 | 只读从库 |

---

## 十、代码与数据位置

| 位置 | 说明 |
|---|---|
| `sql/reconcile_three_way.sql` | 本 Day 的关联 SQL、差异查询、账本 vs 余额、试算平衡 |
| `sql/schema.sql` | `transfer_event_log` / `deposit_record` / `ledger_entry` / `ledger_posting` / `account` DDL |
| `src/main/java/com/ray/web3/controller/ChainController.java` | `saved-count` 与日志列表端点 |
| `src/main/java/com/ray/web3/service/impl/HistoryBackfillServiceImpl.java` | `savedTransferCount` / 补拉 |
| `src/main/java/com/ray/web3/service/impl/DepositRoutingServiceImpl.java` | `deposit_no` 生成、路由、Outbox |
| `src/main/java/com/ray/web3/ledger/LedgerGroupFactory.java` | `DEPOSIT` 的 `biz_no=depositNo` |
| `src/main/java/com/ray/web3/service/impl/DepositCreditService.java` | 消费入账、HOT 拒绝 |
| `src/main/java/com/ray/web3/service/impl/LedgerServiceImpl.java` | 记账内核、`balance_after`、冲正 |
| `src/main/java/com/ray/web3/service/impl/DepositWalletServiceImpl.java` | 配置热钱包 + DB 地址并集 |
| `src/main/resources/application.yml` | `hot-wallets` / `platform-liability-account-id` |

---

## 十一、自测

**① 为什么 `biz_no` 不是 `chain_id + tx_hash + log_index`？**
`ledger_entry.biz_no` 对充值存的是 `deposit_no`（随机业务单号），链上键只存在
`transfer_event_log` / `deposit_record` 的唯一键上；必须经 `deposit_record` 桥接。

**② 为什么不能只查 `ledger_posting`？**
它没有 `biz_no`，只有 `entry_id`；且一笔业务有用户侧和平台侧两条腿，直接求和会翻倍。

**③ 链上 vs 账本为什么要先过滤 `deposit_wallet`？**
`transfer_event_log` 存的是合约全部 Transfer，用户之间的普通转账也在里面；
不过滤会把正常转账误报成“链上有、账本无”。

**④ 充值漏扫怎么发现、怎么补？**
用链上日志列表长度与 `saved-count` 对比；有缺口就调用 backfill，重放 `savedCount=0`。

**⑤ 为什么账本 vs 余额在种子账户上会不平？**
`ledger_test_data.sql` 的种子账户有开账余额但没有开账分录；严格从 0 累加必然不一致。
应使用最近一条 posting 的 `balance_after`，或显式排除种子账户。

**⑥ 为什么不能过滤 `deposit_record.status='CREDITED'`？**
当前没有代码推进 `CREDITED`；消费者在 `PENDING_CONFIRM` 阶段就入账。
加了过滤会把所有已入账充值误报为“链上有、账本无”。

**⑦ 哪些内容留给第 5 周？**
`ReconcileJob`、`reconcile_diff`、六类差异、三类报表、分片/从库、`INIT` 接管、
Reorg 演练、精度差专项；RPC 主备也是未实现的附加项。