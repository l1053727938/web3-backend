# Day 5-6：四类业务分录 + 冲正

> 对应 18 周计划第 2 周 Day 5-6
> 核心目标：四个业务场景各写出一组平衡分录；冲正不修改原分录
> 产出：`LedgerGroupFactory`（四场景 + 冲正构造）+ `LedgerService` 场景方法 + 14 个单测

---

## 一、总体设计：纯函数造分录，服务管事务

```
业务动作
   │
   ▼
LedgerGroupFactory      ← 纯函数：业务动作 → 借贷分录（不碰 DB/事务，可逐条断言）
   │  LedgerGroup
   ▼
LedgerService.post()    ← 事务：平衡校验 → 加锁 → 落分录 → 写回余额
```

把「分录怎么记」和「分录怎么落库」分开，理由是：

- 分录写错是资损级 bug，必须是可枚举、可单测的数据结构，不能散落在业务流程里；
- 服务层只承担机械动作（校验、锁、插入、CAS），逻辑稳定，不需要随业务变化；
- 单测可以只测工厂，不依赖 MySQL，跑得快且断言精确。

---

## 二、四类业务分录

符号约定：**CREDIT = 余额增加，DEBIT = 余额减少**（与重建 SQL 同口径）。

| # | 场景 | bizType | bizNo | 分录 |
|---|---|---|---|---|
| ① | 充值落账 | `DEPOSIT` | 充值单号 | 用户 `available +` / 平台代收负债 `available -` |
| ② | 下单冻结 | `FREEZE` | 订单号 | 用户 `available -` / 用户 `frozen +` |
| ③-a | 成交本金 | `TRANSFER` | 成交号 | Taker `frozen -` / Maker `available +` |
| ③-b | 成交手续费 | `FEE` | 成交号 | 付款方 `available -` / 平台手续费账户 `available +` |
| ④ | 提币失败解冻 | `UNFREEZE` | 原提币单号 | 用户 `frozen -` / 用户 `available +` |

### ① 充值落账

```
用户 SPOT.available      +100 USDT
平台代收负债 PLATFORM    -100 USDT
```

- 金额取链上到账额：`MoneyUtils.toDisplay(raw, decimals)`，和 `deposit_record.display_value` 同源；
- **确认数不足不能落账**：必须等 `deposit_record.status = CREDITED` 后再调用；
- 平台负债账户的 `account_type = 'PLATFORM'`，该类型允许负余额——负债在资产口径下天然越充越负。

### ② 下单冻结

```
用户 available   -30
用户 frozen      +30
```

两条 posting 落在**同一个 account_id 的两个子账**上，`total = available + frozen` 不变，组内借贷仍相等。

### ③ 成交清算：本金与手续费分成两组

本金：

```
Taker frozen    -100
Maker available +100
```

手续费（单独一组 `FEE`，同一 tradeNo）：

```
付款方 available      -1
平台手续费 PLATFORM   +1
```

为什么分开记：

- 本金是用户之间的对赌，手续费是平台收入，混在一起后平台收入无法单独统计；
- 冲正时可以只退本金、不退手续费；
- `bizType` 不同，所以两组可以复用同一个 tradeNo，幂等键互不冲突。

③-a 目前是**单腿（计价币）简化**。真实撮合还有资产腿（例如 BTC），需要用同一个 tradeNo 另记一组不同 bizType 的分录——`uk_biz` 只认 `(biz_type, biz_no)`，同类型同单号只能有一组。

### ④ 提币失败解冻

```
用户 frozen     -30
用户 available  +30
```

与②方向完全相反，`biz_no` 用原提币单号，天然幂等。

**为什么用独立的 `UNFREEZE` 而不是复用 `FREEZE`**：同一个提币单号既要冻结又要解冻，如果共用 bizType 会撞 `uk_biz(biz_type, biz_no)`，第二次调用直接唯一键冲突。`(UNFREEZE, withdrawNo)` 才能重复调用只记一次。

---

## 三、冲正

```java
LedgerService.reverse(String bizType, String bizNo)
```

执行步骤：

1. 按 `(bizType, bizNo)` 找到原 `ledger_entry`；
2. 按原 `entry_id` 读出全部 `ledger_posting`；
3. 每条 posting **方向取反**（`Direction.opposite()`），金额、币种、子账、账户不变；
4. 写一组新分录：
   - `biz_type = REVERSE`
   - `biz_no = REV:<原 entry_no>`
   - `reverse_of = 原 entry_id`
5. 原分录**不修改、不删除**。

### 冲正的三条规则

**1. 幂等**
冲正单号由原 `entry_no` 派生（`REV:LE20261111...`），与 `uk_biz` 配合：同一原分录重复冲正时，第二次撞唯一键，直接返回首次冲正组。

**2. 只冲正一次**
`ledger_entry` 上新增唯一索引 `uk_reverse_of(reverse_of)`。MySQL 唯一索引允许多个 NULL（普通业务行），冲正行的 `reverse_of` 非空，因此每条原分录最多被一条冲正组指向。

**3. 允许余额为负**
冲正是**唯一**允许子账扣成负数的路径（`postInternal(group, allowNegative=true)`）。

原因：用户可能已经把充值金额提走了，此时冲正 100 会让余额变成 -70。如果按正常业务的“余额非负”拦截，这笔账永远更正不了，账本与链上事实会永久偏差。负余额交给对账和风控单独处理，不能在账本层拒绝。

同时禁止对冲正分录再次冲正，避免出现冲正的冲正这种无法解释的链条。

---

## 四、实现细节

### 4.1 `LedgerGroup` 增加 `reverseOf`

```java
public record LedgerGroup(String bizType, String bizNo, List<Posting> postings, Long reverseOf) {
    // 普通业务用三参构造，reverseOf 自动为 null
    public LedgerGroup(String bizType, String bizNo, List<Posting> postings) {
        this(bizType, bizNo, postings, null);
    }
}
```

`buildEntry()` 时写入 `entry.setReverseOf(group.reverseOf())`。

### 4.2 场景方法必须自己开事务

`LedgerServiceImpl` 的场景方法（`deposit/freeze/unfreeze/settle`）内部调用的是 `postInternal(...)`，而不是 `this.post(...)`。

原因：Spring 的 `@Transactional` 基于代理，**同一个 Bean 内部方法互相调用不会经过代理**。如果 `deposit()` 调 `this.post()`，事务注解直接失效，SQL 会在自动提交模式下执行。所以每个场景方法都自己标注：

```java
@Transactional(transactionManager = "ledgerTransactionManager", rollbackFor = Exception.class)
public LedgerEntry freeze(...) {
    return postInternal(LedgerGroupFactory.freezeOrder(...), false);
}
```

`settle()` 里本金和手续费两次 `postInternal` 共用同一个事务：本金成功、手续费失败时一起回滚。

### 4.3 平台账户允许负余额

```java
if (!allowNegative && !isPlatformAccount(account) && after.signum() < 0) {
    throw new LedgerPostingException("子账余额不足 ...");
}

private static boolean isPlatformAccount(Account account) {
    return LedgerConsts.AccountType.PLATFORM.equalsIgnoreCase(account.getAccountType());
}
```

如果不加这个判断，场景①的“平台负债 -100”在平台账户余额为 0 时会被直接拒绝，充值永远落不了账。

### 4.4 账本 Mapper 不继承 `BaseMapper`

`BaseMapper` 自带 `updateById / deleteById`，继承它等于把“改账本”的能力放给业务代码。

现在：

- `LedgerEntryMapper`：手写 `@Insert insert` + `selectByBiz`，没有 update/delete；
- `LedgerPostingMapper`：只有 `insertBatch` 与查询。

配合 `schema.sql` 里的 DB 授权示例（账本两表只给 SELECT/INSERT），做到**代码与数据库双层只增不改**。

> 关于计划里写的“LedgerEntry 无 setter”：实体仍需 setter 供 MyBatis 结果映射使用，去掉会映射失败。用“Mapper 不暴露写改方法 + DB 权限”达到同样的约束，比牺牲 ORM 可用性更现实。

### 4.5 自增主键回填：`@Options`

```java
@Insert("INSERT INTO ledger_entry (entry_no, biz_type, biz_no, status, reverse_of) VALUES (...)")
@Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
int insert(LedgerEntry entry);
```

- `useGeneratedKeys = true`：执行 INSERT 后调用 JDBC `getGeneratedKeys()` 取回自增主键；
- `keyProperty = "id"`：把取回的值回填到入参对象的 `id` 属性；
- `keyColumn = "id"`：从结果集的哪一列读（列名与属性名不同时必须写）。

效果：`insert(entry)` 返回后，`entry.getId()` 已经有值，紧接着插入 `ledger_posting` 时才能写 `entry_id`。不写这个注解，`entry.id` 为 null，明细写入会因 `entry_id NOT NULL` 报错（或写成 0，导致明细永远关联不到分录组）。

批量插入 `insertBatch` 刻意不加 `@Options`：我们不需要 posting 的 ID，且 MySQL 多值 INSERT 的 generated keys 与参数顺序不保证一一对应。

---

## 五、代码位置

| 文件 | 职责 |
|---|---|
| `ledger/LedgerGroupFactory.java` | 四场景 + 冲正的分录构造（纯函数） |
| `service/LedgerService.java` | 对外方法：post / reverse / deposit / freeze / unfreeze / settle |
| `service/impl/LedgerServiceImpl.java` | 事务、加锁、落库、余额回写、冲正流程 |
| `model/ledger/LedgerGroup.java` | 分录组，新增 `reverseOf` |
| `enums/Direction.java` | 新增 `opposite()` |
| `consts/LedgerConsts.java` | 新增 `UNFREEZE`、`AccountType.PLATFORM` |
| `sql/schema.sql` | `uk_reverse_of` 唯一索引、PLATFORM 类型说明、DB 授权示例 |

---

## 六、测试

### `LedgerGroupFactoryTest`（6 个）

- 充值落账：用户 CREDIT / 平台负债 DEBIT，借贷平；
- 下单冻结：同一账户 AVAILABLE DEBIT + FROZEN CREDIT，总额不变；
- 成交清算：本金与手续费各一组、各自平账、可复用同一 tradeNo；
- 提币失败解冻：FROZEN DEBIT + AVAILABLE CREDIT；
- 冲正：方向全部对调、`reverseOf` 指向原 entry、单号 `REV:<entryNo>`；
- 冲正：原分录无明细时拒绝。

### `LedgerServiceImplTest`（8 个）

原有 5 个 `post()` 用例（借贷不平不落库、正常记账与 balance_after、`uk_biz` 幂等、余额不足、币种不一致）之外新增：

- 冲正：写入方向相反的分录并指向原分录，余额正确回写；
- 冲正：允许余额扣成负数（含 PLATFORM 账户）；
- 冲正：原分录不存在时报错且不落库。

合计 **14 个用例全部通过**。

---

## 七、注意事项 / 待办

1. **`uk_reverse_of` 需手动应用到已有库**：
   ```sql
   ALTER TABLE ledger_entry ADD UNIQUE KEY uk_reverse_of (reverse_of);
   ```
   `CREATE TABLE IF NOT EXISTS` 不会补索引。
2. **真实撮合是双腿**：③ 目前只记计价币这一腿，资产腿需要按同一 tradeNo 另记一组。
3. **充值落账前必须确认**：调用 `deposit()` 前确认 `deposit_record.status = CREDITED`，账本层不做链上确认判断。
4. **冲正产生的负余额**需要在对账 Job / 风控里单独处理，否则负余额会一直挂在账户上。
5. **场景方法已提供 HTTP 入口**：`POST /api/ledger/{deposit,freeze,settle,unfreeze,reverse}`，请求体 DTO 见 `model/dto/Ledger*Request`；联调数据见 `sql/ledger_test_data.sql`，一键调用见 `scripts/ledger-api-demo.sh`（或 IDEA 的 `scripts/ledger-api-demo.http`）。

---

## 八、自测问答（对应计划 Day 7）

**① 充值 100 USDT 的分录？确认数不足能不能落账？**
用户 available CREDIT 100 / 平台代收负债 DEBIT 100。确认数不足不能落账，必须等 `CREDITED`。

**② 冻结与清算怎么衔接？撤单释放记什么？**
下单时 available → frozen；成交时 taker frozen 扣减、maker available 增加；撤单释放用方向相反的冻结分录，`biz_no` 用订单号 + 独立 bizType，保证幂等。

**③ 同一 `withdraw_no` 重复落账靠哪层挡？**
数据库唯一键 `uk_biz(biz_type, biz_no)`。应用层查一次再写有竞态，唯一键没有。

**④ scale 取 8 会出什么事？**
账本侧金额被截断到 8 位小数，链上 18 位精度在换算时就丢失，对账永远对不平。所以账本固定 `DECIMAL(38,18)`。

**⑤ 删掉 `account` 能否重建？**
能。按 `account_id + balance_type` 对 `ledger_posting` 做 `SUM(CREDIT) - SUM(DEBIT)` 即可重建 available / frozen。

**⑥ 平台手续费与链上 gas 为什么分两笔？**
手续费是平台向用户收的业务收入；gas 是平台向链付的运营成本。两者科目不同、承担方不同、发生时间也不同，混记会让收入和成本都无法单独核算。

**⑦ 什么场景可以 Redis 预扣减？**
限额类（活动名额、API 配额）可以；账本余额绝对不行——Redis 不是事实来源，宕机/未持久化会直接资损。

---

## 九、HTTP 联调入口与测试数据（补充）

### 9.1 接口清单

| 方法 | 路径 | 对应服务方法 | 说明 |
|---|---|---|---|
| POST | `/api/ledger/post` | `post()` | 通用分录组入口（Day 3-4 已有） |
| POST | `/api/ledger/deposit` | `deposit()` | ① 充值：用户 available ↑ / 平台代收负债 ↓ |
| POST | `/api/ledger/freeze` | `freeze()` | ② 冻结：available ↓ / frozen ↑ |
| POST | `/api/ledger/settle` | `settle()` | ③ 清算：本金 + 可选手续费两组，同事务 |
| POST | `/api/ledger/unfreeze` | `unfreeze()` | ④ 解冻：frozen ↓ / available ↑ |
| POST | `/api/ledger/reverse` | `reverse()` | 冲正：写反向分录，原分录不改 |

所有接口都要求 JWT scope `ledger:write`（本地可先调 `POST /api/auth/token` 签发）。

### 9.2 测试数据

`sql/ledger_test_data.sql` 插入固定 `account.id` 的测试账户（重复执行会重置余额）：

| account.id | 用户 | 类型 | 币种 | 初始可用 | 用途 |
|---|---|---|---|---|---|
| 900001 | 10001 | SPOT | USDT | 1000 | 用户A / Maker |
| 900002 | 10002 | SPOT | USDT | 1000 | 用户B / Taker |
| 900003 | 10003 | SPOT | USDT | 500 | 用户C / 手续费付款方 |
| 900004 | 10001 | SPOT | ETH | 10 | 币种不匹配测试 |
| 900101 | 900000001 | PLATFORM | USDT | 0 | 平台代收负债 |
| 900102 | 900000002 | PLATFORM | USDT | 0 | 平台手续费收入 |

### 9.3 一键联调

```bash
# 1. 初始化表结构与测试账户
mysql -u root -p < sql/schema.sql
mysql -u root -p < sql/ledger_test_data.sql

# 2. 启动应用后执行（自动签发 ledger:write token）
bash scripts/ledger-api-demo.sh
```

也可以在 IDEA 中打开 `scripts/ledger-api-demo.http` 按顺序执行；第 1 个请求会把
`token` 与 `runId` 写入全局变量，后续请求直接引用。

执行完脚本后的期望余额（基于测试数据初始值）：

| 账户 | available | frozen | 说明 |
|---|---|---|---|
| 900001 用户A | 1100 | 0 | 充值 100 后又被冲正 |
| 900002 用户B | 1080 | 20 | 充值 200 → 冻结 150 → 成交扣 100 → 解冻 30 |
| 900003 用户C | 549 | 0 | 充值 50 → 手续费 1 |
| 900101 平台代收负债 | -250 | 0 | 三笔充值合计 350，冲正退回 100 |
| 900102 平台手续费 | 1 | 0 | 手续费收入 |
