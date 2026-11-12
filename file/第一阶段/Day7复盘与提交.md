# Day 7：复盘与提交（第 2 周收尾）

> 对应 18 周计划第 2 周 Day 7
> 目标：把本周做成的东西讲清楚，把没做的列出来，把经验沉淀下来
> 本周主题：账户体系与复式账本基座（二）

---

## 一、本周交付清单

| 交付项 | 状态 | 落点 |
|---|---|---|
| `ledger_entry` / `ledger_posting` 两表 DDL | ✅ | `sql/schema.sql` |
| 重建余额 SQL | ✅ 已写，⚠️ 未在实际库跑 diff | `file/第二阶段/day1-2.md` |
| `post()` 五步：平衡校验 → 排序加锁 → 落分录 → CAS 回写 → 整组回滚 | ✅ | `LedgerServiceImpl` |
| 借贷不平阻断且不落库 | ✅ 单测覆盖 | `LedgerServiceImplTest` |
| `uk_biz` 幂等，重复投递不再动余额 | ✅ 单测覆盖 | 同上 |
| 账本独立连接池 + 独立事务管理器 | ✅ 已启动验证 | `DataSourceConfig` / `LedgerDataSourceConfig` |
| 四类业务分录（充值/冻结/清算/解冻） | ✅ 单测覆盖 | `LedgerGroupFactory` |
| 冲正（`reverse_of`、方向对调、只冲一次） | ✅ 单测覆盖 | `reverse()` |
| 账本只增不改（Mapper 无 update/delete） | ✅ | 两个账本 Mapper 不继承 `BaseMapper` |
| 单测 | ✅ 14 个全绿 | 工厂 6 + 服务 8 |
| 50 线程并发扣同一账户不丢更新 | ⚠️ 未跑 | 见「待改进」 |
| 删表重建后 diff 为空 | ⚠️ 未在库上实跑 | 见「待改进」 |

---

## 二、5 分钟口述稿

### 2.1 为什么冻结不改 total？

> 冻结不改变用户拥有多少资产，只改变这部分资产**能不能用**。
>
> `account.total` 是数据库生成列 `available + frozen`，应用不可写。冻结的分录是同一账户下的两条：
> `available -30`、`frozen +30`。两条都在同一个 account_id 上，只是子账不同，组内借贷天然相等，
> total 一分不变。用户看到的“总资产”不动，只是可用余额减少、冻结余额增加。
>
> 如果冻结去改 total，会出两个问题。第一，用户会以为资产少了，客服解释不清；第二，
> total 是平台对用户负债的口径之一，冻结把它改小，账本、余额、平台负债三方对账立刻对不上。
>
> 真正改变所有权的是**成交清算**：taker 的 frozen 扣减、maker 的 available 增加。
> 冻结只是一个临时占用状态，撤单或提币失败时用方向相反的分录释放，所以冻结和解冻必须成对出现。
>
> 一句话：**total 记录“有多少”，available/frozen 记录“能不能用”；冻结只动后者。**

### 2.2 为什么划转要写单据？

> 因为划转是**账务动作**，不是一次 UPDATE。
>
> 直接 `UPDATE account SET available = available - 100` 有三个致命问题：
> 第一，没有业务事实，事后问“这笔钱为什么少了”答不出来；
> 第二，没有幂等键，接口超时重试会扣两次；
> 第三，删掉余额表之后重建不出来——余额是结果，不是事实。
>
> 正确做法是两层：先写 `account_transfer` 业务单据（谁、何时、从哪到哪、多少、状态），
> 再写一组分录 `FUND.available -100 / SPOT.available +100`。单据是业务事实，分录是会计事实，
> 两者通过单号关联，可以互相对账。
>
> 幂等落在 `transfer_no` 唯一键上：重复提交撞唯一键直接返回原单据，不会二次扣款。
> 出问题时也不改原来的分录，而是写一组方向相反的冲正组，`reverse_of` 指回原分录，
> 这样任何时候都能还原“当时记了什么、后来怎么改的”。
>
> 一句话：**单据回答“发生了什么”，分录回答“钱怎么动的”，余额只是这两者算出来的结果。**

---

## 三、复盘

### 3.1 做对了什么

1. **先定表，再写服务**
   W1 只做 DDL 和 MoneyUtils，没有提前写 `LedgerService`。表结构调整一次（`balance_type`、
   `reverse_of`）成本远低于服务写完再返工。

2. **纯函数工厂 + 服务分层**
   `LedgerGroupFactory` 只负责“业务动作 → 分录”，不碰 DB 和事务；Service 只做机械动作。
   分录是资损级逻辑，现在可以被逐条断言，单测 6 个用例全是纯内存执行。

3. **幂等靠唯一键，不靠先查后写**
   `uk_biz(biz_type, biz_no)` 是最终防线。应用层“查一次再写”在并发下必然有竞态。

4. **写保护做了两层**
   Mapper 不继承 `BaseMapper`（接口上就没有 update/delete），`schema.sql` 里附了只授
   SELECT/INSERT 的 GRANT 示例。代码和数据库同时兜底。

5. **事务管理器显式绑定**
   账本 `@Transactional(transactionManager = "ledgerTransactionManager")` 写死，
   避免连接池隔离后事务落到业务池上的错乱。

6. **冲正只增不改**
   原分录永远不动，靠 `reverse_of` + 反向分录表达更正，`uk_reverse_of` 保证只冲一次。

### 3.2 踩过的坑

| 坑 | 现象 | 解决 |
|---|---|---|
| `CREATE TABLE IF NOT EXISTS` 不补列 | 实体有 `balance_type`，实际库没有，报 Unknown column | 手工 ALTER + 在 schema.sql 末尾写明迁移语句 |
| 解冻复用 `FREEZE` 会撞 `uk_biz` | 同一提币单号既冻结又解冻，第二次唯一键冲突 | 新增独立业务类型 `UNFREEZE` |
| 平台负债账户被余额校验拦住 | 充值落账时平台账户 0 → -100，被判定“余额不足” | `account_type='PLATFORM'` 跳过非负校验 |
| 同类内部调用 `@Transactional` 失效 | 场景方法调 `this.post()`，事务注解不生效 | 每个场景方法自己标事务，调用 `postInternal()` |
| UUID 单号不可读 | 排查时看不出时间顺序 | 改为时间戳 + 同毫秒序号 |
| 多数据源触发自动配置退让 | 加了账本 DataSource 后业务 DataSource/工厂/事务管理器全没了 | 主池四件套全部显式声明并标 `@Primary` |
| 实体 setter 与“不可变”冲突 | 去掉 setter 后 MyBatis 结果映射失败 | 保留 setter，改为 Mapper 层不暴露写改方法 |

### 3.3 待改进

1. **并发验收没跑**
   计划要求“50 线程扣同一账户不丢更新”。当前靠 `FOR UPDATE` + 版本 CAS 设计上成立，
   但没有实际压测数据。补法：起本地 MySQL，用 `CountDownLatch` 起 50 线程并发 `post()`，
   断言最终余额 = 初始 - 50×单笔，且 `ledger_posting` 恰好 100 条。

2. **重建 SQL 没在实际库验证**
   `TRUNCATE account` → 聚合 `ledger_posting` → 与备份 diff 这一步只在文档里写了，
   没有实跑。这是“账本是账本不是流水”的核心证明，建议下周补一次。

3. **四场景方法还没有 HTTP 入口**
   `/api/ledger/post` 是通用入口；`deposit/freeze/settle/unfreeze/reverse` 目前只有服务层方法，
   等对接充值/交易业务时再暴露，避免过早固化接口。

4. **`MybatisFactorySupport` 与 yml 配置双份**
   手写工厂里的驼峰/日志/主键策略等价于 `mybatis-plus.*`，改一处要同步另一处。
   后续改成读取 `MybatisPlusProperties`。

5. **冲正负余额缺后续处理**
   冲正允许把余额扣成负的，但负余额目前没有风控/对账任务跟进，长期会挂在账户上。

6. **`uk_reverse_of` 需要应用到已有库**
   `ALTER TABLE ledger_entry ADD UNIQUE KEY uk_reverse_of (reverse_of);`
   `CREATE TABLE IF NOT EXISTS` 不会补索引。

---

## 四、提交记录

计划里的提交信息是 `feat(ledger): account schema + money utils`，对应 W1，已在早期提交完成。
本周实际提交（按时间）：

| 提交 | 内容 |
|---|---|
| `c89eac2` | feat: 新增 ledger_entry/ledger_posting 分录表 |
| `caff8d6` | feat: 新增复式账本记账接口与 Bean Validation 参数校验 |
| `6f48509` | 添加线程隔离，冲正，解冻等账本业务 |
| `bd3bee9` | feat: 新增账本四类业务与冲正的 HTTP 接口和联调数据 |
| `91ef095` | docs: 新增第二阶段 Day5-6 四类业务分录与冲正 |
| `fd54d95` | docs: 新增第二阶段 day1-2 工作记录 |

---

## 五、下周衔接（第 3 周：消息可靠性与 outbox）

本周账本已经能“正确记账”，但还没有回答“业务侧如何可靠地触发记账”。第 3 周要补：

- outbox：业务事务内写消息表，异步投递，避免“业务成功、记账丢失”；
- 消费幂等：账本侧继续靠 `uk_biz` 消化重复消息；
- 对账 Job：链上流水 ↔ 账本 ↔ 余额三方核对；
- 把本周没跑的并发与重建验证补上。

---

## 六、口述检查表（自测）

- [ ] 能不看文档画出 `post()` 五步，并说清每一步防的是什么故障
- [ ] 能解释 `total` 为什么是生成列、冻结为什么不改它
- [ ] 能解释划转单据与分录的关系，以及为什么不能直接 UPDATE 余额
- [ ] 能说出幂等键为什么必须在数据库上，而不是应用层
- [ ] 能解释冲正为什么允许负余额、为什么原分录不能改
- [ ] 能解释 `UNFREEZE` 为什么不复用 `FREEZE`
- [ ] 能解释账本连接池隔离后，为什么主池四件套要手写
