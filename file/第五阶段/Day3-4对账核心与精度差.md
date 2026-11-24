# Day 3-4：对账核心 + 精度差构造

> 对应 18 周计划第 5 周 Day 3-4
> 目标：跑出 `reconcile_diff`；构造一条真实精度差，证明它不会被误判成遗漏
> 产出：`ReconcileJob`（`ReconcileService`）+ `reconcile_diff` 表 + 一条真实 `PRECISION` 记录

---

## 一、整体流程

```
ReconcileService.run(reportDate)
        │
        ├─ 1. 按日切窗口（deposit_record.created_at，左闭右开）
        │       selectDepositByCreatedAt(from, to)
        │
        ├─ 2. 账本聚合
        │       selectLedgerAgg(bizNos)
        │       - 只取用户侧 AVAILABLE + CREDIT 一条腿
        │       - reversal_count > 0 的组排除（已冲正）
        │
        ├─ 3. 链上孤儿
        │       selectChainOrphans(from, to)
        │       transfer_event_log JOIN deposit_wallet LEFT JOIN deposit_record
        │       命中平台地址但没有充值记录
        │
        ├─ 4. 逐条判定六类（顺序固定）
        │       未知代币 → 重组回滚 → 时间差 → 重复 → 遗漏 → 精度差
        │
        └─ 5. 重建 reconcile_diff
                先 DELETE WHERE report_date = ?，再分批 INSERT
                （同一天重跑结果一致，不做增量累加）
```

---

## 二、`reconcile_diff` 表

```sql
CREATE TABLE IF NOT EXISTS `reconcile_diff` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `report_date`   DATE            NOT NULL,
  `biz_type`      VARCHAR(32)     NOT NULL DEFAULT 'DEPOSIT',
  `biz_no`        VARCHAR(128)    NOT NULL COMMENT '无充值记录的链上孤儿用 CHAIN:tx:log',
  `diff_type`     VARCHAR(24)     NOT NULL COMMENT 'TIMING/MISSING/DUPLICATE/REORG/UNKNOWN_TOKEN/PRECISION',
  `report_group`  VARCHAR(16)     NOT NULL COMMENT 'IN_TRANSIT/OVER_SHORT/UNDETERMINED',
  `direction`     VARCHAR(8)      NULL     COMMENT 'LONG=长款，SHORT=短款',
  `chain_amount`  DECIMAL(38,18)  NULL,
  `ledger_amount` DECIMAL(38,18)  NULL,
  `delta`         DECIMAL(38,18)  NULL     COMMENT 'ledger_amount - chain_amount',
  `currency`      VARCHAR(16)     NULL,
  `evidence`      VARCHAR(512)    NULL,
  `status`        VARCHAR(16)     NOT NULL DEFAULT 'OPEN',
  `created_at`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_date_biz_type` (`report_date`, `biz_no`, `diff_type`),
  KEY `idx_date_group` (`report_date`, `report_group`)
);
```

---

## 三、六类判定（实现顺序）

| 顺序 | 条件 | 结果 | 报表口径 |
|---|---|---|---|
| 1 | 充值记录 `currency` 为空 / 无充值记录的合约不在白名单 | `UNKNOWN_TOKEN` | 待定性 |
| 2 | `deposit_record.status = INVALID` 且账本未冲正 | `REORG` | 在途 |
| 3 | `status = PENDING_CONFIRM` 且账本无分录 | `TIMING` | 在途 |
| 4 | 同一 `biz_no` 账本出现多组 | `DUPLICATE` | 长短款 |
| 5 | `status = CREDITED` 且账本无分录 | `MISSING` | 长短款（短款） |
| 6 | 金额有差且能被 decimals 快照解释 | `PRECISION` | 长短款（按 delta 方向） |
| 7 | 金额有差但不满足精度规则 | 兜底按方向归 `MISSING`/`DUPLICATE` | 长短款 |

方向统一：`delta = ledger_amount - chain_amount`，>0 长款、<0 短款。

---

## 四、精度差专项

### 4.1 原则：历史金额只用快照重算

入账时 `deposit_record` 保存了三个快照：

```
raw_value      链上最小单位整数字符串
decimals       入账当时使用的代币精度
display_value  按该精度换算后的金额（账本应记的金额）
```

重算永远用 `raw_value + 快照 decimals`，**不读当前 decimals**，否则历史订单会随代币精度调整而“漂移”。

### 4.2 判定规则

```
if 当前 decimals == 快照 decimals        → 不是精度差
recomputed = raw_value / 10^当前 decimals
if recomputed == 账本金额 且 != 链上金额 → PRECISION
```

含义：账本是按“当前精度”重算后记的，差额来自换算基准变化，不是漏记。

### 4.3 构造的真实案例

**数据**：

| 字段 | 值 |
|---|---|
| `deposit_no` | `PREC-20261124-1` |
| `raw_value` | `10000000` |
| `decimals`（快照） | `6` |
| `display_value` | `10.000000`（链上应为 10 USDT） |
| 账本入账金额 | `0.1` USDT（按 `decimals=8` 重算：`10000000 / 10^8`） |
| 当前 decimals 覆盖 | `8` |

**执行**：

```bash
# 1) 起应用，覆盖当前精度
java -jar ... --web3.reconcile.current-decimals[0x5fbdb...]=8

# 2) 用账本接口写入 0.1（模拟“按当前精度重算后入账”）
TOKEN=$(curl -s -X POST "http://127.0.0.1:18080/api/auth/token?sub=ops&scope=ledger:write" \
  -H "X-Issuer-Key: local" | sed -E 's/.*"token":"([^"]+)".*/\1/')
curl -X POST http://127.0.0.1:18080/api/ledger/post \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"bizType":"DEPOSIT","bizNo":"PREC-20261124-1","postings":[
        {"accountId":900104,"direction":"CREDIT","amount":"0.1","currency":"USDT"},
        {"accountId":900101,"direction":"DEBIT","amount":"0.1","currency":"USDT"}]}'

# 3) 跑对账
curl -X POST "http://127.0.0.1:18080/api/reconcile/run?date=2026-11-24"
```

**结果（真实执行输出）**：

```
{"code":0,"message":"ok","data":{
  "reportDate":"2026-11-24","total":1,"inTransit":0,"overShort":1,
  "undetermined":0,"netDelta":-9.900000000000000000}}
```

差异明细：

```
2026-11-24 | PREC-20261124-1 | PRECISION | OVER_SHORT | SHORT |
chain=10.000000000000000000 | ledger=0.100000000000000000 |
delta=-9.900000000000000000 | USDT | decimals snapshot=6 current=8
```

接口返回：

```json
{
  "bizNo": "PREC-20261124-1",
  "diffType": "PRECISION",
  "reportGroup": "OVER_SHORT",
  "direction": "SHORT",
  "chainAmount": 10.000000000000000000,
  "ledgerAmount": 0.100000000000000000,
  "delta": -9.900000000000000000,
  "evidence": "decimals snapshot=6 current=8"
}
```

**关键点**：这条记录如果按 `decimals=6` 重算会显示“账本少 9.9”，很容易被当成遗漏补记；
正确做法是识别为精度差，修换算口径而不是补钱。

### 4.4 重跑幂等

同一日期再次执行：

```
POST /api/reconcile/run?date=2026-11-24
→ total=1
SELECT COUNT(*) FROM reconcile_diff WHERE report_date='2026-11-24';
→ 1
```

先删后插，不会出现两行同一业务差异。

---

## 五、接口

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/api/reconcile/run?date=yyyy-MM-dd` | 执行对账；不传 date 默认今天 |
| `GET` | `/api/reconcile/diff?date=yyyy-MM-dd` | 查差异明细 |

> 内部运维接口，当前未纳入 JWT；生产接入前应加鉴权与操作审计。

---

## 六、代码位置

| 文件 | 职责 |
|---|---|
| `service/ReconcileService.java` | 接口 + `ReconcileReport` 汇总 |
| `service/impl/ReconcileServiceImpl.java` | 取数、六类判定、精度差、落库 |
| `mapper/ReconcileQueryMapper.java` | 充值与账本聚合、链上孤儿查询（只读） |
| `mapper/ReconcileDiffMapper.java` | 按日重建差异表 |
| `model/entity/ReconcileDiff.java` | 差异实体 |
| `model/vo/LedgerAggRow.java` / `ChainOrphanRow.java` | 查询结果行 |
| `consts/ReconcileConsts.java` | 六类/三类/方向/状态常量 |
| `config/ReconcileProperties.java` | 白名单、当前 decimals 覆盖、cron |
| `controller/ReconcileController.java` | 手动触发与查询 |
| `scanner/ReconcileScanner.java` | 每日自动跑前一天（默认关闭） |

---

## 七、测试

`ReconcileServiceImplTest`（2 个用例）：

1. **一天内六类差异全部正确分类**
   - 构造 6 条充值记录 + 2 条链上孤儿；
   - 断言 TIMING / MISSING / PRECISION / DUPLICATE / REORG / UNKNOWN_TOKEN 各自命中；
   - 断言报表口径：2 在途 + 4 长短款 + 2 待定性；
   - 断言净差 `-19.9`（符号一致）。
2. **重跑先删同日结果再重建**。

---

## 八、边界与后续

**本次实现**：

- 按 `deposit_record.created_at` 日切窗口；
- 账本按 `biz_no` 批量聚合（500 一批）；
- 链上孤儿通过 `deposit_wallet` 关联（配置中心热钱包若未落库暂不覆盖）；
- 精度差通过 `web3.reconcile.current-decimals` 覆盖或链上 `decimals()` 获取当前值；
- 金额兜底：无法用精度解释的金额差按方向归入 `MISSING`/`DUPLICATE`，`evidence` 标注“未命中精度规则”。

**后续（第 5 周 Day 5-6）**：

1. 对账数据源切只读从库；
2. 按 `user_id` 分片 + 线程池 + 全局限流；
3. `INIT` 提币三态接管与状态转换表；
4. Reorg 回滚演练（`anvil_reorg` / `evm_snapshot`）；
5. 差异处置流程（补记、冲正、人工核销）与日报导出。
