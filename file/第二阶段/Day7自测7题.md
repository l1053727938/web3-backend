# Day 7：自测 7 题（详解）

> 对应 18 周计划第 2 周 Day 7
> 用途：合上代码能答出来，才算这周真的做完
> 每题都给出「结论 + 为什么 + 在本项目里的落点」

---

## ① 充值 100 USDT 的分录？确认数不足能不能落账？

**分录（一组，`bizType=DEPOSIT`，`bizNo=充值单号`）：**

```
用户 SPOT.available        CREDIT 100 USDT
平台代收负债 PLATFORM      DEBIT  100 USDT
```

- 符号约定：`CREDIT` = 余额增加，`DEBIT` = 余额减少；
- 平台负债账户 `account_type='PLATFORM'`，允许负余额（负债在资产口径下越充越负）；
- 金额 = 链上到账额：`MoneyUtils.toDisplay(raw, decimals)`，与 `deposit_record.display_value` 同源。

**确认数不足不能落账。**

`deposit_record` 的生命周期是：

```
PENDING_CONFIRM（已落库，确认数未达阈值）
        │  确认数 ≥ confirm-threshold
        ▼
     CREDITED（可以记账）
        │
     链重组回滚
        ▼
      INVALID（永不记账）
```

原因：区块可能重组。刚进块的事件在重组后可能消失，如果先记账再回滚，就要写冲正、还要处理“用户已花掉”的负余额。所以账本只消费 `CREDITED`，确认数判断属于链上监听/充值落地层，账本层不做链上判断。

**落点**：`LedgerGroupFactory.deposit(...)` / `LedgerService.deposit(...)`。

---

## ② 冻结与清算怎么衔接？撤单释放记什么？

三个阶段，三组分录：

**下单冻结**（`FREEZE`，订单号）：

```
用户 available   DEBIT  30
用户 frozen      CREDIT 30
```

同一账户、两个子账，`total` 不变。

**成交清算**（`TRANSFER`，成交号）：

```
Taker frozen      DEBIT  100
Maker available   CREDIT 100
```

手续费另起一组（`FEE`，同一成交号）：

```
付款方 available      DEBIT  1
平台手续费 PLATFORM   CREDIT 1
```

**撤单释放**（`UNFREEZE`，订单号）：

```
用户 frozen      DEBIT  30
用户 available   CREDIT 30
```

与冻结方向完全相反，金额是**剩余未成交部分**（部分成交时只释放剩余冻结）。

**为什么用 `UNFREEZE` 而不是复用 `FREEZE`**：同一个订单号既冻结又解冻，如果共用 `bizType` 会撞 `uk_biz(biz_type, biz_no)`，重复调用直接唯一键冲突。`(UNFREEZE, orderNo)` 才能天然幂等。

**衔接关系一句话**：冻结只占用，成交才转移所有权，撤单/失败把占用退回；三者金额守恒，`total` 只在“平台手续费”这一步真正变化。

---

## ③ 同一 `withdraw_no` 重复落账靠哪层挡？

**数据库唯一键 `uk_biz(biz_type, biz_no)`。**

流程：

1. `INSERT ledger_entry`；
2. 撞唯一键 → MyBatis-Spring 抛 `DuplicateKeyException`；
3. 服务层捕获后 `selectByBiz(bizType, bizNo)` 回读原分录组；
4. 直接返回原 entry，**不再插入 posting、不再动余额**。

为什么不能靠应用层“先查再写”：两个并发请求可能同时查到“不存在”，然后各写一遍。查与写之间的窗口就是竞态。唯一键把判断交给数据库，天然串行。

业务层还有一层：`withdraw_record.uk_withdraw_no` 保证提币单本身不重复。

注意解冻要用 `(UNFREEZE, withdraw_no)` 而不是 `(FREEZE, withdraw_no)`——同一单号的冻结与解冻是两个独立事实，共用 bizType 会互相撞键。

**落点**：`LedgerServiceImpl.postInternal()` 的 `catch (DuplicateKeyException e)` 分支。

---

## ④ scale 取 8 会出什么事？

账本金额是 `DECIMAL(38,18)`，`MoneyUtils.SCALE = 18`。如果改成 8：

1. **精度直接丢失**
   链上最小单位是 wei，`1 wei = 0.000000000000000001 ETH`（18 位小数）。scale=8 时它会被截断成 0。
   `MoneyUtils` 的舍入方向是 `DOWN`（平台不吃用户零头），所以每一笔小额都会**少算**。

2. **误差会累积**
   单笔 1e-9 看不出，10 万笔充值就是 1e-4。对账时 `链上流水 vs 账本` 的差额会随交易量线性增长，且**不可复现**——因为每笔截断发生在不同位置。

3. **对账永远对不平**
   差额会同时出现在：账本 vs 余额、链上 vs 账本、平台负债 vs 用户总额三条线上。排查时无法判断是漏账、重复还是精度问题。

4. **DECIMAL(38,8) 的整数位变成 30 位**
   看起来能装更大的数，但账本真正需要的是小数精度，不是整数位。uint256 最大 78 位，本来也用不到 30 位整数。

**结论**：账本精度只能向上对齐链上精度（18 位），不能向下取整。改 `MoneyUtils.SCALE` 之前，必须同时改 DDL、`DECIMAL(38,18)`、重建 SQL 和对账规则，三处不同口径就是资损。

---

## ⑤ 删掉 `account` 能否重建？

**能。** 余额是分录的聚合结果，不是独立事实。

```sql
SELECT account_id,
       SUM(CASE WHEN balance_type = 'AVAILABLE'
                THEN CASE direction WHEN 'CREDIT' THEN amount ELSE -amount END
                ELSE 0 END) AS available,
       SUM(CASE WHEN balance_type = 'FROZEN'
                THEN CASE direction WHEN 'CREDIT' THEN amount ELSE -amount END
                ELSE 0 END) AS frozen
FROM ledger_posting
GROUP BY account_id;
```

- `total` 是生成列 `available + frozen`，不需要单独重建；
- 冲正不会破坏重建：冲正就是一组方向相反的正常 posting，聚合时自动抵消；
- 校验方法：`TRUNCATE account` → 用上面的结果回填 → 与备份逐行 diff，差值为空才算通过。

**两个前提**：

1. 账户的元数据（`user_id / account_type / currency`）重建不出来，`ledger_posting` 只有 `account_id`，需要从备份或用户表恢复；
2. `ledger_posting` 本身不能被删改——这正是账本 Mapper 不暴露 update/delete、DB 账号只授 SELECT/INSERT 的原因。

**意义**：能重建才叫账本；不能重建的只是流水。面试问“删表能重建吗”，答案是能，并且能说清重建 SQL 与前提。

---

## ⑥ 平台手续费与链上 gas 为什么分两笔？

两者是**不同方向、不同对手方、不同科目**的钱：

| | 平台手续费 | 链上 gas |
|---|---|---|
| 性质 | 业务收入 | 运营成本 |
| 谁付给谁 | 用户 → 平台 | 平台 → 矿工/验证者 |
| 记账科目 | `FEE`，用户 available↓ / 平台手续费账户↑ | 平台费用，热钱包 ETH 减少 |
| 发生时机 | 每次成交/提币按规则收 | 每笔链上交易实际消耗 |
| 金额决定因素 | 费率 × 成交额 | gasUsed × gasPrice，随拥堵波动 |

混在一起会出三个问题：

1. **收入与成本无法分别核算**。手续费是毛利来源，gas 是运营成本，混记后两个指标都失真；
2. **对手方不同**。手续费是用户与平台之间的债权债务，gas 是平台与链之间的消耗，冲正/退款时处理方式完全不同；
3. **粒度不对齐**。一笔提币可能批量打包多笔用户提现，gas 是链上维度的，手续费是业务单维度的，按笔也配不上。

所以项目里：`withdraw_record` 记用户手续费，`gas_cost` 记链上成本，账本里的手续费分录是 `FEE` 业务类型，gas 不进用户账本。

---

## ⑦ 什么场景可以 Redis 预扣减？

**可以：限额类、可补偿、允许最终一致的场景。**

- 接口限流 / 每日配额（本项目 `RateLimitInterceptor` 的日配额）；
- 活动名额、优惠券库存、秒杀库存；
- 短期并发控制（分布式锁、信号量）。

判断标准：**扣错了能不能补、超一点会不会资损**。限流多放一个请求最多是压力问题；库存多卖一个可以退款/补偿。

**绝对不行：账本余额。**

原因：

1. **Redis 不是事实来源**。宕机、主从切换、未持久化、内存淘汰都可能丢数据；余额在 Redis 里“减成功”但 DB 没落，或反过来，都会直接资损。
2. **无法与账本对账**。账本的核心价值是“可重建、可审计”，Redis 的一次性扣减不产生分录，事后无法还原。
3. **并发下并不更安全**。`DECR` 是原子的，但“扣减 + 写分录 + 回写余额”整体不是原子的；用 Redis 做余额只会把一致性问题从数据库挪到一个更不可控的地方。
4. **正确的做法已经在项目里**：`SELECT ... FOR UPDATE` 串行化 + `version` 乐观锁 CAS + 分录幂等键。热点账户的优化方向是分片账户/串行队列，不是把余额搬到 Redis。

**一句话**：Redis 可以做“准入的门槛”，不能做“记账的账本”。

---

## 附：一句话答案速记

| # | 一句话 |
|---|---|
| ① | 用户 available +100 / 平台负债 -100；确认数不到 `CREDITED` 不落账 |
| ② | 冻结占用、成交转移、撤单释放；撤单记 `UNFREEZE`（frozen→available） |
| ③ | 数据库唯一键 `uk_biz(biz_type, biz_no)`，撞键返回原分录 |
| ④ | scale=8 会截断 wei 级金额，误差累积，对账永远对不平 |
| ⑤ | 能重建：按 account_id + balance_type 聚合 `SUM(CREDIT) - SUM(DEBIT)` |
| ⑥ | 手续费是用户给平台的收入，gas 是平台给链的成本，科目/对手方/时机都不同 |
| ⑦ | 限额类可以；账本余额绝对不行，Redis 不是事实来源 |
