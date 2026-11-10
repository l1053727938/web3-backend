```markdown
# Day 1-2（W2）：分录模型与"删表可重建"——详细讲解

> 对应 18 周计划第 2 周开篇
> 目标：先把"账本长什么样"定死，证明它是账本不是流水
> 产出：两表 DDL + 重建 SQL（含符号修正版）+ diff SQL

---

## 一、为什么必须分 `ledger_entry` 和 `ledger_posting` 两张表

### 1.1 一笔业务 = 一个原子组 + N 条明细

复式记账的核心：一笔业务至少涉及两个账户的变动（有借必有贷）。账本天然是两层：

- **组层**：这笔业务是什么（业务类型 + 业务单号 + 状态）——一笔业务一行；
- **明细层**：这笔业务具体动了哪几个账户、各往哪个方向动多少——一个账户一行，条数不定。

路线原话："一组分录是一个原子业务，`uk_biz` 放在组上才能做幂等；明细只管金额，条数不定。"

### 1.2 用场景 ① 充值 100 USDT 看两层长什么样

```
ledger_entry（1 行）
entry_no = E20261109001
biz_type = DEPOSIT
biz_no   = chain_id:tx_hash:log_index
status   = SUCCESS
│
│ entry_id 关联
├──────────────────────────────┐
▼                              ▼
ledger_posting（2 行）           ledger_posting
account_id = 热钱包               account_id = 用户FUND.available
direction  = DEBIT               direction  = CREDIT
amount     = 100                 amount     = 100
currency   = USDT                currency   = USDT
balance_after = ...              balance_after = ...
```

### 1.3 为什么不能合成一张表（三个理由）

**理由一：幂等键必须挂在组上。**
`uk_biz(biz_type, biz_no)` 表达的是"这笔业务只允许一次"。一笔充值只有一个 `biz_no`，重复提交时命中唯一键冲突，返回原 entry，不再动余额。如果只有一张明细表，唯一键会退化成"同一账户同一方向同一金额只能一条"——这显然不对，同一用户两笔各 100 的充值完全合法。

**理由二：明细条数不定。**
场景 ③ 冻结是 2 条 posting，场景 ④ 手续费是 2 条，但完整成交清算可能是 4 条、6 条甚至更多（Taker 冻结扣、Maker 可用加、Taker 手续费、Maker 手续费……）。组表固定一行，明细表 N 行，结构才装得下。

**理由三：审计入口清晰。**
排查"这笔充值为什么没到账"，先按 `biz_no` 查 `ledger_entry` 有没有这行——没有就是没落账；有，再查它的 posting 看动没动对账户。两层结构让排查有明确入口，不用在一张宽表里猜。

---

## 二、逐字段拆解：每个字段在回答什么问题

### 2.1 `ledger_entry`（分录组）

| 字段 | 回答什么问题 | 设计要点 |
|---|---|---|
| `entry_no` | 这组分录的对外单号 | 全局唯一，用于日志/工单引用；与 `biz_no` 不同——`biz_no` 是业务单号，`entry_no` 是账本单号 |
| `biz_type` | 这是什么业务 | `DEPOSIT / TRANSFER / FREEZE / FEE / REVERSE ...`；和 `biz_no` 组成幂等键 |
| `biz_no` | 这笔业务的唯一标识 | 充值用 `chain_id:tx_hash:log_index`，划转用 `transfer_no`，冻结用 `order_no`——**必须是业务侧天然唯一的值** |
| `status` | 这组分录成没成 | `SUCCESS / REVERSED`（被冲正）；注意不是 INIT/SUCCESS/FAILED——分录要么整体成功要么不存在，失败靠事务回滚，不需要 FAILED 态 |
| `created_at` | 什么时候记的 | 审计、对账按日切窗口用 |

**关键设计：`uk_biz(biz_type, biz_no)`**。注意是**两列联合唯一**，不是 `biz_no` 单列唯一。因为不同 `biz_type` 可能复用同一个单号（比如 `order_no` 既用于冻结也用于清算），单列唯一会误伤。

### 2.2 `ledger_posting`（分录明细）

| 字段 | 回答什么问题 | 设计要点 |
|---|---|---|
| `entry_id` | 属于哪组分录 | 关联 `ledger_entry.id`；**不是外键**（分库后外键不可用），靠应用保证 |
| `account_id` | 动了谁的账 | 关联 `account.id`；重建余额的分组键 |
| `direction` | 记在借方还是贷方 | `DEBIT / CREDIT` 枚举，不是 `+1/-1`——方向与账户类型解耦 |
| `amount` | 动了多少 | `DECIMAL(38,18)`，永远正数，方向由 `direction` 表达 |
| `currency` | 什么币 | 跨币种时同一 entry 下会有多币种 posting，按币种拆组校验平衡 |
| `balance_after` | 这笔操作后余额是多少 | **可审计性来源**，见下 |

### 2.3 `balance_after` 为什么是可审计性来源

路线原话："事后能回答'这笔操作之后余额是多少'，对账出差异时靠它定位。"

展开：假设对账发现某账户余额和账本算出来的不一致。如果没有 `balance_after`，你只能从头重放所有 posting 去算每个时点的余额，慢且容易错。有了 `balance_after`，你可以直接拿某条 posting 的 `balance_after` 和当时 `account` 表里的值对比，**快速定位是哪一笔操作之后开始对不上的**。

**注意一个工程细节**：`balance_after` 存的是哪个余额？是 `available`、`frozen` 还是 `total`？建议**存该 posting 影响的那个子账的余额**（动 available 就存 available 的 after，动 frozen 就存 frozen 的 after），并在 posting 里隐含"动的是哪个子账"——最干净的做法是 `account` 表按子账拆行（`available` 和 `frozen` 各占一列，但 posting 里通过 `direction` + 账户语义确定），或者 posting 加一个 `sub_account` 字段。路线没强制，但**必须团队内统一**，否则重建和审计会对不上。

---

## 三、重建逻辑：为什么"删表能重建"是账本和流水的分水岭

### 3.1 重建在做什么

```
1. 备份 account 表（重建前的余额快照）
2. TRUNCATE account
3. 从 ledger_posting 聚合出每个账户的余额，写回 account
4. 与备份逐行 diff，必须为空
```

如果 diff 为空，说明：**账本里记录的每一笔变动，恰好能还原出当前余额**——账本和余额是同一事实的两种表示，没有"账本漏记"或"余额被黑改"。

### 3.2 为什么流水做不到

流水（transaction log）只记"发生了什么"，不记"钱从哪个账户流到哪个账户、各流多少"。比如流水记"用户 A 于 10:00 发起充值 100"，但没有"热钱包 debit 100 / 用户 credit 100"这层账户维度信息。删掉 `account` 表，你从流水重建不出余额——**流水里没有 `account_id + direction + amount` 这组三元组**。

**分录比流水多的那一层，就是"账户维度的方向 + 金额"。** 这是"删表能重建"成立的根本原因，也是路线说"证明你写的是账本，不是流水"的含义。

### 3.3 面试怎么答

> "我们账本分 entry + posting 两层，posting 记 `account_id + direction + amount + balance_after`。删掉 account 余额表后，按 `account_id` 聚合 posting，`CREDIT` 加、`DEBIT` 减，能重建出每个账户余额，和备份 diff 为空。这说明账本是余额的唯一事实源，不是流水。"

**能当场说"能，我做过"**——这是路线点名的面试加分项。因为大部分候选人只会说"我们用了复式记账"，但没真做过重建验证，一追问"删表能重建吗"就露馅。

---

## 四、重建 SQL 怎么写——路线给的版本有一个必须修正的符号坑

### 4.1 路线原版

```sql
SELECT account_id,
       SUM(CASE direction WHEN 'CREDIT' THEN amount ELSE -amount END)
FROM ledger_posting
GROUP BY account_id
```

### 4.2 这个版本对谁成立、对谁不成立

它默认"**CREDIT 为正、DEBIT 为负**"。这只对**负债/收入/权益类**账户成立（它们增加记贷方）。对**资产类和费用类**，增加记借方，应该是 `DEBIT 为正`。不分符号会导致资产账户重建出的余额符号反了。

**所以正确版本必须按 `account_class` 分符号**：

```sql
SELECT p.account_id,
       SUM(CASE
             WHEN a.account_class IN ('LIABILITY','INCOME','EQUITY')
                  AND p.direction = 'CREDIT' THEN p.amount
             WHEN a.account_class IN ('LIABILITY','INCOME','EQUITY')
                  AND p.direction = 'DEBIT'  THEN -p.amount
             WHEN a.account_class IN ('ASSET','EXPENSE')
                  AND p.direction = 'DEBIT'  THEN p.amount
             WHEN a.account_class IN ('ASSET','EXPENSE')
                  AND p.direction = 'CREDIT' THEN -p.amount
           END) AS reconstructed_balance
FROM ledger_posting p
JOIN account a ON a.id = p.account_id
GROUP BY p.account_id;
```

### 4.3 一个必须讲清的边界：CREDIT 为正 vs DEBIT 为正

- **站在"负债视角"**（用户余额是负债）：CREDIT 为正、DEBIT 为负。路线原版是这个视角。
- **站在"资产视角"**（平台热钱包是资产）：DEBIT 为正、CREDIT 为负。
- **重建必须按账户类型分别处理**，不能一刀切。

**面试点**：被问"重建 SQL 对资产账户和负债账户用同一个符号吗"，答"按 account_class 分符号，负债类 CREDIT 为正、资产类 DEBIT 为正"就显专业。只答"CREDIT 为正"会被追问出破绽。

### 4.4 重建后的 diff 怎么做

```sql
-- 备份：CREATE TABLE account_bak AS SELECT * FROM account;
-- 重建：TRUNCATE account; 执行上面的聚合 INSERT 回 account;
-- diff：逐行比对，应为空
SELECT b.id, b.available, a.available, b.frozen, a.frozen
FROM account_bak b
JOIN account a ON a.id = b.id
WHERE b.available <> a.available OR b.frozen <> a.frozen;
-- 期望：0 行
```

**注意**：diff 要比 `available` 和 `frozen`，不必比 `total`（生成列，自动一致）。金额比较在 SQL 里用 `<>` 对 DECIMAL 是数值比较，没问题；如果在应用层 diff，用 `compareTo`。

---

## 五、两表 DDL（今天的产出物之一）

```sql
-- ledger_entry：分录组
CREATE TABLE `ledger_entry` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `entry_no`    VARCHAR(64)     NOT NULL                COMMENT '账本单号，全局唯一',
  `biz_type`    VARCHAR(32)     NOT NULL                COMMENT '业务类型: DEPOSIT/TRANSFER/FREEZE/FEE/REVERSE',
  `biz_no`      VARCHAR(128)    NOT NULL                COMMENT '业务单号，与 biz_type 联合幂等',
  `status`      VARCHAR(16)     NOT NULL DEFAULT 'SUCCESS' COMMENT 'SUCCESS/REVERSED',
  `reverse_of`  BIGINT UNSIGNED NULL                    COMMENT '冲正时指向原 entry_id，原分录不改',
  `created_at`  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_entry_no` (`entry_no`),
  UNIQUE KEY `uk_biz` (`biz_type`, `biz_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分录组：一笔业务一行，幂等键在组上';

-- ledger_posting：分录明细
CREATE TABLE `ledger_posting` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `entry_id`       BIGINT UNSIGNED NOT NULL                COMMENT '所属分录组',
  `account_id`     BIGINT UNSIGNED NOT NULL                COMMENT '账户ID，重建余额的分组键',
  `direction`      VARCHAR(8)      NOT NULL                COMMENT 'DEBIT/CREDIT',
  `amount`         DECIMAL(38,18)  NOT NULL                COMMENT '金额，永远正数',
  `currency`       VARCHAR(16)     NOT NULL                COMMENT '币种',
  `balance_after`  DECIMAL(38,18)  NOT NULL                COMMENT '本笔操作后该子账余额，审计用',
  `created_at`     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_entry` (`entry_id`),
  KEY `idx_account_created` (`account_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分录明细：一个账户一行，条数不定';
```

**三个设计说明**：

1. **`ledger_posting` 不建 `uk_entry_account_direction` 之类的唯一键**——同一 entry 下同一账户可能出现两条同方向 posting（复杂清算），唯一键会误伤。防重复靠 `ledger_entry.uk_biz`，不靠 posting。
2. **`idx_account_created`**：重建余额（按 account_id 聚合）和对账（按 account_id + 时间窗口）都走这个索引。
3. **不给 `ledger_posting` 开 UPDATE/DELETE 权限**（DB 账号层面），配合路线 W2 Day 5-6 的"禁止 UPDATE/DELETE"——分录只能 INSERT，错了走冲正。

---

## 六、冲正与"原分录永远不动"（今天先埋，Day 5-6 展开）

路线 W2 Day 5-6 讲冲正：新增 `reverse_of` 指向原 entry，写一组方向相反的分录，**原分录永远不动**。

今天的两表 DDL 要能支持它——所以 `ledger_entry` 的 `status` 有 `REVERSED`，`reverse_of` 字段现在加：

```sql
`reverse_of` BIGINT UNSIGNED NULL COMMENT '冲正时指向原 entry_id，原分录不改'
```

现在加的好处：表结构一次定死，W2 写冲正不用 `ALTER TABLE`。代价是多一列、暂时全 NULL——可接受。

---

## 七、今天的自测（合上回答）

1. 为什么分 `ledger_entry` 和 `ledger_posting` 两张表？三个理由各是什么？
2. `uk_biz` 为什么是 `(biz_type, biz_no)` 联合唯一，不是 `biz_no` 单列唯一？
3. `direction` 为什么用枚举不用 `+1/-1`？`amount` 为什么永远正数？
4. `balance_after` 存的是什么？缺了它对账出差异时会发生什么？
5. 重建逻辑的四步是什么？diff 为空说明什么？
6. 路线原版重建 SQL（`CREDIT` 为正）对哪类账户不成立？正确写法怎么分符号？
7. "删表能重建"为什么是账本和流水的分水岭？流水缺了哪层信息？
8. 为什么 `ledger_posting` 不建"同 entry 同账户同方向"唯一键？防重复靠什么？

---

## 八、自测答案

**1. 三个理由**：① 幂等键必须挂在组上（`uk_biz` 表达"这笔业务只一次"）；② 明细条数不定，组固定一行、明细 N 行才装得下；③ 审计入口清晰（先查 entry 有没有，再查 posting 动没动对）。

**2. 联合唯一的原因**：不同 `biz_type` 可能复用同一单号（`order_no` 既用于冻结也用于清算），单列唯一会误伤。联合唯一才表达"同一业务类型下同一单号只一次"。

**3. 枚举 + 正数的原因**：`direction` 是栏位名不是正负号，余额是加是减取决于账户类型；`amount` 存正数、方向由 `direction` 表达，让 posting 结构与账户类型解耦，同一套结构既能表达资产侧也能表达负债侧。

**4. `balance_after` 的含义**：该 posting 影响的那个子账在操作后的余额，是可审计性来源。缺了它，对账出差异时只能从头重放所有 posting 算每个时点余额，慢且易错，无法快速定位"哪一笔之后开始对不上"。

**5. 重建四步**：备份 account → TRUNCATE → 按 account_id 聚合 posting 写回 → 与备份逐行 diff。diff 为空说明"账本记录的变动恰好能还原当前余额"，账本是余额的唯一事实源。

**6. 符号坑**：路线原版 `CREDIT 为正` 只对负债/收入/权益类成立；资产类和费用类增加记借方，应 `DEBIT 为正`。正确写法按 `account_class` 分四种情况处理。

**7. 分水岭的原因**：流水只记"发生了什么"，缺"account_id + direction + amount"这层账户维度信息，删表重建不出余额；分录多了这层，所以能重建。"删表能重建"= 账本是余额的事实源，不是日志。

**8. 不建唯一键 + 防重复靠什么**：同一 entry 下同一账户可能两条同方向 posting（复杂清算），唯一键会误伤。防重复靠 `ledger_entry.uk_biz(biz_type, biz_no)`——组级幂等，不是明细级。

---

## 九、今天的产出与下一步

**产出**：
1. `ledger_entry` + `ledger_posting` 两表 DDL（可执行）
2. 重建 SQL（按 `account_class` 分符号版）+ diff SQL
3. `SHOW CREATE TABLE` 存档

**下一步（Day 3-4）**：写 `post()` 五步——校验平衡 → 按 accountId 排序防死锁 → 同事务插 entry+posting → 逐账户 CAS 更新余额 → `@Transactional` 回滚。今天的表结构和重建 SQL 是它的前提：**表没定死、重建没跑通，不要写 `post()`**（路线避坑：表没定死就写服务，第三周一定返工）。

---

## 十、避坑清单（本日）

| 坑 | 正确做法 |
|---|---|
| 把 entry 和 posting 合成一张表 | 必须分两层，幂等键挂组上 |
| `uk_biz` 用 `biz_no` 单列唯一 | 用 `(biz_type, biz_no)` 联合唯一 |
| `direction` 存 `+1/-1` | 用 `DEBIT/CREDIT` 枚举 |
| `amount` 存负数 | 永远正数，方向由 direction 表达 |
| 不存 `balance_after` | 必须存，它是差异定位的入口 |
| 重建 SQL 一刀切 `CREDIT` 为正 | 按 `account_class` 分符号 |
| posting 建"同账户同方向"唯一键 | 不建，防重复靠 entry 的 `uk_biz` |
| 给 posting 开 UPDATE/DELETE 权限 | 只给 INSERT/SELECT，错了走冲正 |
| 表没定死就写 `post()` | 先跑通重建 + diff 为空，再写服务 |
```

以上为可直接保存的 `.md` 文档，包含：
- 分录模型两层设计理由（3 个）
- 逐字段拆解 + `balance_after` 可审计性说明
- 重建逻辑四步 + "账本 vs 流水"分水岭论证
- 重建 SQL 的符号坑 + 按 `account_class` 修正版
- 两表完整 DDL（含 `reverse_of` 预留）
- 冲正设计伏笔
- 8 道自测 + 完整答案
- 产出清单 + 下一步 + 10 条避坑