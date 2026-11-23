# Day 1-2：差错六类与三类报表

> 对应 18 周计划第 5 周 Day 1-2（🔴 P1 死磕）
> 目标：把对账差异**定义清楚**，六类 → 三类报表的映射可直接当日报模板
> 数据源：`sql/reconcile_three_way.sql`（链上 `transfer_event_log` / 账本 `ledger_entry+ledger_posting` / 余额 `account`）

---

## 一、先定符号：长款 / 短款

对账的最小粒度是**一笔业务**（`deposit_no` 或链上事件键），每笔算一个差值：

```
delta = 账本已入账金额 - 链上应入账金额

delta > 0   → 长款（账上比链上多）
delta = 0   → 平
delta < 0   → 短款（账上比链上少）
```

- **长款**：平台多记了用户余额/负债，潜在付出；
- **短款**：平台少记了用户的钱，用户资产缺口。

两条红线：

1. **全系统只允许这一个符号定义**。报表、告警、SQL 都用 `账本 - 链上`，否则日报里一正一负会互相抵消。
2. **长款/短款只统计“已定性为资损方向”的差异**，在途类差异不进这两个口径（见第三节）。

---

## 二、差错六类

每类都回答四个问题：**怎么判定、典型数据长什么样、归哪张报表、怎么处置**。

### ① 时间差（TIMING）

**定义**：链上事件已发生，但还没到确认数，账本按规则尚未入账。

**判定**：

```sql
-- 有充值记录，状态仍是待确认，且没有账本分录
SELECT dr.deposit_no, dr.block_number, dr.amount ...
FROM deposit_record dr
LEFT JOIN ledger_entry le
       ON le.biz_type = 'DEPOSIT' AND le.biz_no = dr.deposit_no
WHERE dr.status = 'PENDING_CONFIRM'
  AND le.id IS NULL;
```

**归属**：在途。

**处置**：什么都不做，等确认数达标；超过阈值（如 2 小时仍未确认）转人工确认链上状态。

**为什么不算短款**：钱还在路上，不是平台欠用户的钱。报成短款会触发资损熔断，第二天全平台停止提币——这是对账系统最典型的事故。

---

### ② 遗漏（MISSING）

**定义**：链上已经确认的入账，账本里没有对应分录。

有两种形态，都要识别：

**②-a 漏路由**：链上事件命中平台地址，但根本没有 `deposit_record`。

```sql
SELECT te.chain_id, te.transaction_hash, te.log_index, te.to_address, te.raw_value
FROM transfer_event_log te
LEFT JOIN deposit_record dr
       ON dr.chain_id = te.chain_id
      AND dr.transaction_hash = te.transaction_hash
      AND dr.log_index = te.log_index
WHERE te.chain_id = ?
  AND te.contract_address = ?
  AND te.to_address IN (平台收款地址集合)
  AND dr.id IS NULL;
```

**②-b 漏入账**：`deposit_record` 已是 `CREDITED`，但账本没有 `(DEPOSIT, deposit_no)`。

```sql
SELECT dr.deposit_no, dr.display_value
FROM deposit_record dr
LEFT JOIN ledger_entry le
       ON le.biz_type = 'DEPOSIT' AND le.biz_no = dr.deposit_no
WHERE dr.status = 'CREDITED'
  AND le.id IS NULL;
```

**归属**：长短款（短款，delta < 0）。

**处置**：补记账本（幂等 `biz_no`），并追查消费者/路由日志；连续出现说明链路有 bug，不是人工补单能收尾的。

**与时间差的区别**：时间差是“还没到时候”，遗漏是“时候到了但没记”。

---

### ③ 重复（DUPLICATE）

**定义**：链上一笔，账本记了两笔或更多。

**判定**：

```sql
-- 同一业务键出现多组分录
SELECT biz_no, COUNT(*) AS entry_count
FROM ledger_entry
WHERE biz_type = 'DEPOSIT'
GROUP BY biz_no
HAVING COUNT(*) > 1;
```

另一种形态：同一链上事件（tx_hash + log_index）对应了多个 `deposit_no`（正常被唯一键挡住，出现即异常）。

**归属**：长短款（长款，delta > 0）。

**处置**：保留最早一组，其余写冲正（`REVERSE`），**不删除**；同时修消费端幂等缺口。

**为什么唯一键还会重复**：`uk_biz` 只防同一 `biz_no`；如果上游生成了两个不同 `deposit_no` 指向同一事件，唯一键挡不住——所以还要按链上事件键做交叉校验。

---

### ④ 重组回滚（REORG）

**定义**：曾经确认并入账的充值，因链重组被回滚（日志 `removed`、交易消失或所在块被替换）。

**判定**（组合判断）：

```sql
-- 已入账记录被标记为 INVALID
SELECT dr.deposit_no, dr.block_number, dr.transaction_hash
FROM deposit_record dr
WHERE dr.status = 'INVALID';

-- 同时账本侧仍有效（尚未冲正）
SELECT le.biz_no, le.entry_no
FROM ledger_entry le
WHERE le.biz_type = 'DEPOSIT'
  AND le.biz_no IN (SELECT deposit_no FROM deposit_record WHERE status = 'INVALID')
  AND NOT EXISTS (
      SELECT 1 FROM ledger_entry r
      WHERE r.biz_type = 'REVERSE' AND r.reverse_of = le.id
  );
```

**归属**：在途。

**处置**：确认重组稳定后，写冲正分录；链上重新出现的交易按新事件重新入账。

**关键**：虽然此时 `账本 > 链上`（看起来像长款），但它**不能计入长款**。重组是暂时状态，下一轮对账会自然收敛；计入长款会造成日报金额剧烈抖动，也会触发不必要的资损告警。

---

### ⑤ 未知代币（UNKNOWN_TOKEN）

**定义**：到账合约不在平台支持的代币清单里（或缺少 decimals/币种映射），无法确定币种与精度，账本拒绝入账。

**判定**：

```sql
SELECT DISTINCT te.contract_address, COUNT(*) AS event_count, MAX(te.block_number) AS last_block
FROM transfer_event_log te
LEFT JOIN deposit_record dr
       ON dr.chain_id = te.chain_id
      AND dr.transaction_hash = te.transaction_hash
      AND dr.log_index = te.log_index
WHERE te.to_address IN (平台收款地址集合)
  AND te.contract_address NOT IN (
      -- 平台代币白名单：web3.deposit.token-symbols 的合约集合，或 token_metadata 表
      '0x5fbdb2315678afecb367f032d93f642f64180aa3'
  )
GROUP BY te.contract_address;
```

**归属**：待定性。

**处置**：补齐代币白名单/精度/币种映射后重放；补齐前**不进入长短款**，否则会把“不认识”误报成“钱丢了”。

---

### ⑥ 精度差（PRECISION）

**定义**：两边金额不一致，但差异恰好来自 decimals 或舍入规则不一致，而不是少记/多记。

**判定思路**：

1. 该笔有 `deposit_record` 且有账本分录；
2. `delta = ledger - chain` 不为 0；
3. **用入账时的 decimals 快照重算**，结果与账本一致；
4. 再用“当前 decimals”重算，结果与链上金额一致；
5. 两次换算的倍率正好是 `10^(当前 decimals - 快照 decimals)`。

```sql
-- 快照字段：deposit_record.decimals；链上原始值：deposit_record.raw_value / transfer_event_log.raw_value
SELECT dr.deposit_no,
       dr.raw_value,
       dr.decimals AS snapshot_decimals,
       dr.display_value AS booked_amount,
       -- 用当前精度重算的金额（示意，实际在应用层用 BigDecimal 计算）
       CAST(dr.raw_value AS DECIMAL(65, 0)) / POW(10, 当前decimals) AS recomputed_amount
FROM deposit_record dr
WHERE dr.status = 'CREDITED';
```

**归属**：长短款（方向按 `delta`）。

**处置**：以**入账快照**为准，修的是“重算逻辑”而不是账本；需要调整时才写冲正/补记，禁止批量改历史金额。

**为什么它不是遗漏**：钱在账上，只是两边换算基准不同；按遗漏处理会重复入账。

---

## 三、判定顺序（决策树）

分类必须按固定顺序，否则一笔差异会被重复归类：

```
1. 代币不在白名单 / 缺 decimals        → 待定性（未知代币）
2. 链上事件已消失或 deposit_record=INVALID → 在途（重组回滚）
3. 未达确认数 / 无账本分录              → 在途（时间差）
4. 同一业务键账本出现多组                → 长短款（重复）
5. 已确认但账本无分录                    → 长短款（遗漏）
6. 金额有差且能被 decimals 快照解释      → 长短款（精度差）
7. 以上都不匹配                          → 待定性（人工）
```

先判“无法定性”和“暂时状态”，再判“真差异”，最后才落到长短款。

---

## 四、六类 → 三类映射表（日报模板）

| 差错类型 | 判定要点 | 链上 | 账本 | delta 方向 | 归属报表 | 资损告警 | 处置 |
|---|---|---|---|---|---|---|---|
| 时间差 | 未达确认数 | 有 | 无 | — | **在途** | 否 | 等待/确认链上 |
| 重组回滚 | 曾确认后被回滚 | 无 | 有 | 账上多但仍归在途 | **在途** | 否 | 稳定后冲正 |
| 遗漏 | 已确认无分录 | 有 | 无 | 短款 | **长短款** | 是（短款优先） | 补记 + 查链路 |
| 重复 | 同键多组 | 一笔 | 多笔 | 长款 | **长短款** | 是 | 保留最早 + 冲正其余 |
| 精度差 | 快照可解释差额 | 有 | 有 | 长/短款 | **长短款** | 是 | 修换算逻辑 |
| 未知代币 | 不在白名单 | 有 | 无 | — | **待定性** | 否（先补配置） | 补白名单后重放 |

### 三类报表口径

| 报表 | 包含 | 回答的问题 |
|---|---|---|
| **在途** | 时间差、重组回滚 | 有多少钱还在路上/待确认，什么时候能收敛 |
| **长短款** | 遗漏、重复、精度差 | 平台实际多了/少了多少钱，要追谁 |
| **待定性** | 未知代币及无法分类项 | 有多少差异暂时看不懂，缺什么配置/信息 |

> 口径只有这三类。把六类直接当日报分类，会出现“时间差短款”“重组长款”这种互相打架的结论。

---

## 五、三类报表模板

### 5.1 日报总表（按币种汇总）

| 字段 | 说明 |
|---|---|
| `report_date` | 对账日（UTC 或本地，全系统统一） |
| `currency` | 币种 |
| `chain_count / chain_amount` | 链上应入账笔数/金额（已确认 + 平台地址） |
| `ledger_count / ledger_amount` | 账本已入账笔数/金额（有效 DEPOSIT） |
| `in_transit_count / in_transit_amount` | 在途合计 |
| `over_count / over_amount` | 长款合计（delta > 0） |
| `short_count / short_amount` | 短款合计（delta < 0，金额取绝对值） |
| `pending_count / pending_amount` | 待定性合计 |
| `net_delta` | `Σ delta`（带符号，等于长款 − 短款） |
| `status` | 平 / 有差异 / 熔断 |

**恒等式**（必须成立，否则报表自身算错）：

```
chain_amount = ledger_amount - net_delta - in_transit_net_delta
```

其中在途按“链上有、账本无”计 `delta = -amount`，重组按“链上无、账本有”计 `delta = +amount`。
如果对不上，先查分类是否重复计数（同一 biz_no 同时进了在途和长短款）。

### 5.2 在途明细

| 字段 | 示例 |
|---|---|
| `biz_no` | DP1117... |
| `type` | TIMING / REORG |
| `tx_hash` / `log_index` | 0x... / 3 |
| `block_number` | 104 |
| `amount` / `currency` | 10.000000 / USDT |
| `detected_at` | 2026-11-23 21:00 |
| `expected_confirm_at` | 2026-11-23 21:30 |
| `status` | WAITING / REVERTED |

### 5.3 长短款明细

| 字段 | 示例 |
|---|---|
| `biz_no` | DP... / 空（②-a 漏路由时为空） |
| `type` | MISSING / DUPLICATE / PRECISION |
| `direction` | LONG（长款） / SHORT（短款） |
| `chain_amount` | 10.000000 |
| `ledger_amount` | 11.000000 |
| `delta` | +1.000000 |
| `evidence` | 快照 decimals=6 / 当前 decimals=8 |
| `ticket_no` | 处置单号 |
| `owner` | 责任人 |

### 5.4 待定性明细

| 字段 | 示例 |
|---|---|
| `contract_address` | 0x... |
| `event_count` | 12 |
| `first_seen_at` | 2026-11-23 |
| `sample_tx` | 0x... |
| `missing_config` | token-symbols / decimals 白名单 |
| `status` | OPEN / CONFIGURED |

---

## 六、判定查询草稿（基于现有表）

> 全部只读；生产对账应指向**只读从库**，且按日窗口 + 分片执行，不要全表扫主库。

统一参数：`@chain_id`、`@contract`、`@from_block`、`@to_block`、`@cutoff_confirm`。

### 6.1 平台收款地址集合

```sql
WITH platform_addr AS (
    SELECT LOWER(address) AS address FROM deposit_wallet
    UNION
    SELECT LOWER(address) FROM (VALUES ('0x70997970c51812dc3a010c7d01b50e0d17dc79c8')) t(address)
)
```

### 6.2 链上应入账（已确认）

```sql
SELECT te.transaction_hash, te.log_index, te.to_address, te.contract_address, te.raw_value
FROM transfer_event_log te
JOIN platform_addr pa ON pa.address = LOWER(te.to_address)
WHERE te.chain_id = @chain_id
  AND te.contract_address = @contract
  AND te.block_number BETWEEN @from_block AND @to_block;
```

### 6.3 账本已入账（用户侧腿）

```sql
SELECT le.biz_no, lp.amount, lp.currency
FROM ledger_entry le
JOIN ledger_posting lp ON lp.entry_id = le.id
WHERE le.biz_type = 'DEPOSIT'
  AND lp.balance_type = 'AVAILABLE'
  AND lp.direction = 'CREDIT';
```

### 6.4 六类判定（伪 SQL 汇总）

```sql
-- 时间差
SELECT dr.deposit_no, 'TIMING'
FROM deposit_record dr
LEFT JOIN ledger_entry le ON le.biz_type='DEPOSIT' AND le.biz_no=dr.deposit_no
WHERE dr.status='PENDING_CONFIRM' AND le.id IS NULL;

-- 遗漏（漏入账）
SELECT dr.deposit_no, 'MISSING'
FROM deposit_record dr
LEFT JOIN ledger_entry le ON le.biz_type='DEPOSIT' AND le.biz_no=dr.deposit_no
WHERE dr.status='CREDITED' AND le.id IS NULL;

-- 重复
SELECT biz_no, 'DUPLICATE'
FROM ledger_entry
WHERE biz_type='DEPOSIT'
GROUP BY biz_no HAVING COUNT(*) > 1;

-- 重组回滚
SELECT dr.deposit_no, 'REORG'
FROM deposit_record dr
WHERE dr.status='INVALID'
  AND EXISTS (
      SELECT 1 FROM ledger_entry le
      WHERE le.biz_type='DEPOSIT' AND le.biz_no=dr.deposit_no
        AND NOT EXISTS (SELECT 1 FROM ledger_entry r
                        WHERE r.biz_type='REVERSE' AND r.reverse_of=le.id)
  );
```

未知代币与精度差需要在应用层（`ReconcileJob`）用白名单与快照计算，SQL 只筛候选集。

---

## 七、对账窗口与幂等

1. **窗口**：按日切（`block_time` 或 `block_number` 区间），窗口两端都闭合，跨窗口不重叠；
2. **截止点**：只统计“确认数已达标”的链上事件，未达标一律进在途；
3. **快照**：金额换算只用 `deposit_record.decimals`，不读当前 `token_metadata`；
4. **幂等**：同一窗口重复跑只允许覆盖同一份 `reconcile_diff`（按 `report_date + biz_no + diff_type` 唯一），不能重复累计；
5. **只读**：对账读取走从库；补账/冲正是另一个有审计的写流程，二者分开。

---

## 八、常见误区

| 误区 | 正确做法 |
|---|---|
| 时间差报成短款 | 归在途；短款会触发熔断停提币 |
| 重组回滚报成长款 | 归在途；重组是暂时状态，下一轮自动收敛 |
| 用当前 decimals 重算历史 | 用入账快照，否则精度差会被误判成遗漏 |
| 未知代币直接进长短款 | 先补白名单；不认识 ≠ 丢钱 |
| 六类直接当日报分类 | 六类是判定，日报只输出在途/长短款/待定性 |
| 对账跑主库、单线程全表扫 | 只读从库 + 按日窗口 + 分片并发 + 限流 |
| 差异直接改余额 | 补记/冲正走账本，原分录不动 |

---

## 九、自测（对应 Day 7）

1. 时间差为什么归在途？——钱在路上，不是平台欠款；误报短款会熔断停提币。
2. 账上多 10 USDT 是长款还是短款？——`delta = 账本 - 链上 > 0`，长款。
3. 重组期间能不能判短款？——不能；归在途，待冲正后收敛。
4. `INIT` 被同 nonce 顶替怎么处理？——判 FAILED 并解冻（第 5 周 Day 5-6 的状态转换表）。
5. 单线程扫表为什么打挂主库？——大表全扫 + 主库承担写入，需从库 + 窗口 + 分片 + 限流。

---

## 十、本日产出

- 六类差错的定义、判定 SQL 草案、处置动作；
- 六类 → 三类报表的映射表（可直接作为日报模板）；
- 三类报表字段与样例；
- 符号与恒等式约定，以及五个最常见的分类误区。
