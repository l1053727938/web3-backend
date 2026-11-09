# Day 3-4 与 Day 5-6 知识点 + 自测答案（完整 md 文档）

```markdown
# Day 3-4 与 Day 5-6 知识点总结（含自测答案）

> 对应 18 周计划第 1 周：账户体系与复式账本基座（一）后半段
> Day 3-4：三张表 DDL　｜　Day 5-6：金额双轨制与舍入

---

# 一、Day 3-4：三张表 DDL

## 1. 三张表各是什么

| 表 | 定位 | 唯一键 |
|---|---|---|
| `account` | 账户余额表（余额的物化） | `uk_user_type_currency (user_id, account_type, currency)` |
| `account_transfer` | 划转单（业务事实层） | `uk_transfer_no (transfer_no)` |
| `perp_position` | 合约持仓表（独立于余额） | `uk_user_symbol_side_mode (user_id, symbol, side, margin_mode)` |

## 2. 金额精度

- 账本侧统一 `DECIMAL(38,18)`，禁用 `DOUBLE` / `FLOAT`（一次都不行）。
- 链上侧继续用 `VARCHAR(78)` 存 raw uint256，账本侧存 display 值——金额双轨制。
- 38 位总长、18 位小数：覆盖 ETH 18 位精度，USDT(6)/BTC(8) 都在范围内。

## 3. `total` 生成列

```sql
total DECIMAL(38,18) GENERATED ALWAYS AS (available + frozen) STORED
```

- **作用**：让 `total = available + frozen` 由 DB 强制保证，应用**语法上无法给 total 赋值**。
- **价值**：从"靠应用自觉"升级成"靠表结构保证"，避免漏写/写错导致恒等式静默破裂。
- **STORED vs VIRTUAL**：选 `STORED`，因为账本读多写少，total 要参与报表聚合和索引；写时多一次加法可忽略。
- **验证动作**：`INSERT ... total=999` 必须报 `ERROR 3105`；不报错说明 total 不是生成列。

## 4. 唯一键设计

- `account` 唯一键必须含 `account_type`，不能是 `(user_id, currency)`——同一用户同一币种可同时有 FUND/SPOT/PERP 三个子账。
- 唯一键是**所有 CAS 的前提**：`UPDATE ... WHERE id=? AND version=?` 能精确定位唯一一行，CAS 才成立。

## 5. `version` 乐观锁

- 不是装饰：所有扣减走 `UPDATE ... SET version=version+1 WHERE id=? AND version=?`，影响行数为 0 就重试/回滚。
- **唯一键 + version 是一对**：唯一键保证"锁哪一行"明确，version 保证"这一行有没有被别人改过"可检测。缺唯一键 CAS 锁错行，缺 version CAS 检测不到并发。

## 6. `account_transfer` 设计

- `transfer_no` 是**幂等键**，必须由上游/客户端生成并透传，不能 DB 自增（自增重试时生成新单号，幂等失效）。
- `status` 三态 `INIT / SUCCESS / FAILED`：先落 INIT（业务事实先落地）→ 写分录 → 改 SUCCESS；崩溃后靠 INIT 被对账 Job 捞出补偿。
- 正确顺序：单据 INIT → 写分录 → 单据 SUCCESS，三步同一事务。

## 7. `perp_position` 为什么独立成表

- 持仓有 `side / leverage / entry_price / margin_mode / margin`，和"余额"是两个维度。
- 塞进 `account` 会：① `available+frozen=total` 对持仓行不成立，生成列/CHECK 失效；② 聚合余额要处处过滤持仓行；③ 主键装不下多 symbol 多方向。
- 唯一键含 `margin_mode`：同一 symbol 同一方向，逐仓和全仓可同时存在。

## 8. CHECK 约束的版本坑

- MySQL **8.0.16 之前** `CHECK` 被解析但**忽略**，脏数据照样能插。
- 若 `total` 是生成列，`CHECK (total = available+frozen)` **恒为真**（total 就是 DB 算的），加了没坏处也拦不住什么。
- **真正的保证来自生成列，不是 CHECK**；生成列从 MySQL 5.7 稳定支持，不受版本坑影响。

## 9. 验证脚本（验收动作）

| 验证 | SQL | 期望 |
|---|---|---|
| 生成列生效 | `INSERT (available=100, frozen=0)` | `total` 自动 = 100 |
| 应用不能写 total | `INSERT ... total=999` | 报 `ERROR 3105` |
| 唯一键生效 | 重复插 `(1,'SPOT','USDT')` | 报 `ERROR 1062 Duplicate` |
| CHECK 行为 | 旧版本插 `available=1,frozen=1,total=999` | 8.0.16+ 报错 / 之前不报错 |
| 建表存档 | `SHOW CREATE TABLE` × 3 | 贴进 `docs/` 或截图 |

## 10. Day 3-4 自测要点 + 答案

### 自测 1：`total` 为什么做成生成列？`STORED` 和 `VIRTUAL` 怎么选？

**答案**：让 `total = available + frozen` 由数据库强制保证，应用语法上无法给 total 赋值，从"靠自觉"升级成"靠结构"，避免漏写/写错导致恒等式静默破裂。同时生成列可建索引、可参与报表聚合，语义上声明 total 是派生值。

`STORED` vs `VIRTUAL`：选 `STORED`——账本读多写少，total 要参与报表聚合和索引，读性能和索引能力更重要；写入时多算一次加法可忽略。

### 自测 2：唯一键为什么是 `(user_id, account_type, currency)` 而不是 `(user_id, currency)`？

**答案**：因为同一用户同一币种可同时有多个账户类型（FUND/SPOT/PERP）。若唯一键是 `(user_id, currency)`，就变成"用户 USDT 只能有一个账户"，合约和现货无法隔离，风控会把现货钱算进保证金。唯一键必须含 `account_type`，才能表达"一个用户 × 一个账户类型 × 一个币种 = 一行"。

### 自测 3：`version` 和唯一键为什么"是一对，缺一不可"？

**答案**：唯一键保证"扣减的目标行唯一"，version 保证"并发扣减不丢更新"，两者解决不同问题但互相依赖。
- CAS 写法 `UPDATE ... WHERE id=? AND version=?` 能成立的前提是 `WHERE id=?` 能精确定位唯一一行——唯一键失效则 CAS 锁错行。
- 只有唯一键没有 version，两线程同时读到 `version=5` 都执行更新，后写覆盖先写（丢更新）。
- 一句话：唯一键让"锁哪一行"明确，version 让"这一行有没有被别人改过"可检测。

### 自测 4：`transfer_no` 为什么必须由上游生成，不能 DB 自增？

**答案**：因为它是幂等键，幂等键必须是"业务方重试时能带回来的同一个值"。客户端/网关重试带同一 `transfer_no`，服务端靠 `uk_transfer_no` 命中冲突识别"这笔做过了"，返回原结果。若服务端 DB 自增，重试时生成新单号，唯一键不冲突，第二遍照常执行——重复划转，幂等失效。自增 ID 是"服务端生成、客户端不知道"的，天然做不了幂等键。

### 自测 5：`perp_position` 为什么必须独立成表？唯一键为什么含 `margin_mode`？

**答案**：持仓有 `side/leverage/entry_price/margin_mode/margin`，和"余额"是两个维度。塞进 `account` 会导致：① `available+frozen=total` 对持仓行不成立，生成列/CHECK 失效；② 聚合余额要处处过滤持仓行；③ 主键 `(user_id, account_type, currency)` 装不下多 symbol 多方向。

唯一键含 `margin_mode`：同一用户同一 symbol 同一方向，逐仓和全仓可同时存在，不含 `margin_mode` 会把"逐仓多单"和"全仓多单"判为冲突、无法共存。

### 自测 6：验证 2（手写 total 报错）验证的是什么？没报错说明什么？

**答案**：验证 `total` 确实是生成列、应用不能显式赋值。SQL：`INSERT ... total=999`，期望 `ERROR 3105: The value specified for generated column 'total' ... is not allowed`。

没报错说明 `total` 不是生成列（或建表语句漏了 `GENERATED ALWAYS AS`），此时 total 是普通可写字段，应用能随便写 999，恒等式失去强制保证——必须回去修 DDL。这条是"生成列是否真生效"的试金石。

### 自测 7：MySQL 8.0.16 之前 CHECK 有什么坑？为什么生成列不受影响？

**答案**：8.0.16 之前 `CHECK` 被解析但被忽略——语法接受、`SHOW CREATE TABLE` 能看到，但插脏数据时不校验，是摆设。

生成列不受影响：`GENERATED ALWAYS AS (...) STORED` 从 MySQL 5.7 稳定支持，它保证的是"total 由 DB 计算"，不是"total 被校验"——应用没机会写 total，也就没有"校验是否生效"的问题。生成列是"结构上不可能写错"，CHECK 是"写了之后检查对不对"，前者不依赖版本行为。

---

# 二、Day 5-6：金额双轨制与舍入

## 1. 双轨制的两层

| 层 | 存储 | 原因 |
|---|---|---|
| 链上侧 | `VARCHAR(78)` 存 raw uint256 + `decimals` 快照 | 链上金额是整数最小单位，天然无损；转 DECIMAL 反引入精度风险 |
| 账本侧 | `DECIMAL(38,18)` + `BigDecimal` | 需要精确十进制运算，禁用 double/float |

## 2. 换算公式（固定不变）

```
display = raw / 10^decimals
raw     = display × 10^decimals
```

- `decimals` 从 `token_metadata` 表读，**不实时调合约**（一次 RPC 换一个精度，对账会疯）。
- 落账时把 `decimals` 和 `raw_amount` 存**快照**，重算用快照而非当前值——这是 W5 精度差识别的地基。

## 3. 舍入规则

- 全局唯一：建议 `RoundingMode.DOWN`（平台不吃用户零头，资损方向安全）。
- 在 `MoneyUtils` 类注释写死"全系统只允许 DOWN，改之前先改对账规则"。
- 舍入统一不只是"好看"，也是让两端舍入后仍相等（配合 `post()` 借贷平衡校验）。

## 4. `MoneyUtils` 三个方法

```java
toDisplay(BigInteger raw, int decimals)   // raw → BigDecimal display
toRaw(BigDecimal display, int decimals)   // display → BigInteger raw
normalize(BigDecimal)                     // 统一 scale=18 + DOWN
```

## 5. 四个精度单测

| 用例 | 输入 | 期望 |
|---|---|---|
| USDT(6) | `1000000` | `1.000000` |
| ETH(18) 1 wei | `1` | `0.000000000000000001` |
| toRaw 反向可逆 | display → raw → display | 与原始一致 |
| 超长小数 | `1.2345678901234567891` | DOWN 后 scale=18，不抛异常（检查是否该抛） |

## 6. BigDecimal 工程细节

- 金额比较用 `compareTo`，**不用 `equals`**（`equals` 比 scale，`100.00` ≠ `100`）。
- 禁用 `double` / `float`，一次都不行。
- 统一 scale：`normalize` 里定死 scale=18，避免不同路径 scale 不一致导致比较失败。

## 7. 与 W1/W2 的衔接

- `post()` 校验 `sum(DEBIT) == sum(CREDIT)` 用 `compareTo`，不平抛 `UnbalancedLedgerException` + 资损告警，不落库、不自动修正。
- 重建余额 SQL 要按 `account_class` 分符号：资产/费用类 `DEBIT` 为正，负债/收入/权益类 `CREDIT` 为正——不能一刀切 `CREDIT` 为正。
- `balance_after` 存的是 normalize 后的值，保证审计链一致。

## 8. 双轨制与跨币种

- 跨币种交易（如 BTC/USDT）**按币种拆组**，每组内 debit=credit，跨币种不比金额。
- 每组用各自 `decimals` 换算，`MoneyUtils` 按币种参数调用，不混用。

## 9. Day 5-6 自测要点 + 答案

### 自测 1：为什么链上侧用 VARCHAR 不用 DECIMAL？

**答案**：链上金额是整数最小单位（uint256），天然无损；DECIMAL 有精度上限（38 位装不下 uint256 的 78 位），转成 DECIMAL 反而引入精度风险。链上侧保持 `VARCHAR(78)` 存 raw uint256 + `decimals` 快照，换算在账本侧做。

### 自测 2：decimals 为什么从表读、不实时调合约？

**答案**：实时调合约意味着每查一次余额/每算一次金额都要一次 RPC，性能差且不稳定；更致命的是**同一代币的 decimals 可能变（合约升级/代理）**，如果实时读，历史数据重算时会用新精度，对账永远不平。从 `token_metadata` 表读 + 落账时存快照，才能保证历史数据可重现。

### 自测 3：为什么全局只允许 DOWN？

**答案**：DOWN（向下舍入）意味着平台不吃用户零头，资损方向安全——多出来的尾数留在用户侧，平台不会因为舍入多收用户的钱。如果混用 HALF_UP/UP，不同路径舍入方向不一致，会导致两端舍入后不相等（`post()` 平衡校验失败）和对账差异。统一 DOWN 是"资损安全 + 两端一致"的双重选择。

### 自测 4：金额比较为什么用 compareTo 不用 equals？

**答案**：`BigDecimal.equals` 比较的是"值 + scale"，`100.00` 和 `100` 用 `equals` 不相等，用 `compareTo` 才相等。金额比较关心的是数值是否相等，不是 scale 是否一致，所以必须用 `compareTo`。写错会导致"明明平了却抛异常"或"明明不等却通过"。

### 自测 5：四个精度单测各验什么？

**答案**：
1. USDT(6) `1000000` → `1.000000`：验证 6 位小数的基本换算。
2. ETH(18) `1` wei → `0.000000000000000001`：验证 18 位精度不丢。
3. `toRaw` 反向可逆：验证 `toDisplay` 和 `toRaw` 互为逆运算，无精度损失。
4. 超长小数 `1.2345678901234567891`：验证 DOWN 后 scale=18、不抛异常，确认"超长输入"的行为符合预期（是否该抛由团队定，单测锁定行为）。

### 自测 6：重建余额为什么按 account_class 分符号？

**答案**：`SUM(CASE direction WHEN 'CREDIT' THEN amount ELSE -amount END)` 默认"CREDIT 为正"，这只对负债/收入/权益类成立（它们增加记贷方）。对资产类和费用类，增加记借方，应该"DEBIT 为正"。不分符号会导致资产账户重建出的余额符号反了，对账永远不平。所以重建 SQL 必须按 `account_class` 分四种情况处理。

### 自测 7：跨币种怎么保证平衡？

**答案**：跨币种交易（如 BTC/USDT）不能把两种币放一组比金额（50000 USDT ≠ 1 BTC），必须**按币种拆组**：USDT 组（买方 USDT 减 50000 / 卖方 USDT 加 50000）、BTC 组（卖方 BTC 减 1 / 买方 BTC 加 1），每组内部同币种、debit=credit。"借贷相等"是**同币种内**的约束，跨币种只保证"每种币各自守恒"。

---

# 三、两日知识串联（一句话版）

> **Day 3-4 定"钱存在哪、怎么防并发"**：三张表 + 生成列 total + 唯一键 + version CAS + 单据幂等；**Day 5-6 定"钱怎么表示、怎么算准"**：链上 VARCHAR 双轨、账本 DECIMAL(38,18) + BigDecimal、DOWN 舍入、compareTo 比较、按 account_class 分符号重建。**表结构是骨架，金额精度是血肉，两者合起来 W2 才能写 `post()` 不返工。**

---

# 四、避坑清单（两日合并）

| 坑 | 正确做法 |
|---|---|
| 表没定死就写 LedgerService | Day 3-4 只建表 + 验证，W2 再写服务 |
| total 做成普通字段 | 必须生成列 STORED |
| 唯一键漏 account_type | 必须 `(user_id, account_type, currency)` |
| transfer_no DB 自增 | 上游生成并透传 |
| 金额用 double/float | 一律 DECIMAL + BigDecimal |
| decimals 实时调合约 | 从 token_metadata 读 + 存快照 |
| 舍入规则各处不同 | 全局 DOWN，写进类注释 |
| 金额比较用 equals | 用 compareTo |
| 重建 SQL 一刀切 CREDIT 为正 | 按 account_class 分符号 |
| 信 CHECK 能拦脏数据 | 生成列才是真保证，CHECK 旧版本被忽略 |
| 跨币种分录放一组比金额 | 按币种拆组，每组内 debit=credit |
```
