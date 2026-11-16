
```markdown
# W3 Day 1-2 详解：Kafka 概念 + Outbox 表

> 来源：CEX 后端 18 周每日计划 · 第 3 周 Day 1-2
> 定位：整个 18 周可靠性设计的起点。不是教"怎么用 Kafka"，而是论证"为什么消息必须和业务写在同一个事务里"。

---

## 目录

- [一、Kafka 四件事（🟡 P2：只要结论，不碰源码）](#一kafka-四件事p2只要结论不碰源码)
- [二、核心推理："至少一次"意味着一定会重复](#二核心推理至少一次意味着一定会重复)
- [三、两个"不能"：为什么必须 Outbox](#三两个不能为什么必须-outbox)
- [四、outbox_message 表设计](#四outbox_message-表设计)
- [五、Day 3-4 会接上的两个细节](#五day-3-4-会接上的两个细节)
- [六、验收标准](#六验收标准)
- [七、面试口述版](#七面试口述版)
- [附：FOR UPDATE SKIP LOCKED 详解](#附for-update-skip-locked-详解)
- [附：Outbox 名称与本质详解](#附outbox-名称与本质详解)

---

## 一、Kafka 四件事（🟡 P2：只要结论，不碰源码）

计划明确"只取结论，不碰源码"。目标是能用四句话讲清 Kafka 的可靠性模型，并推出后面的幂等结论。

### 1. 分区（Partition）

- Topic 物理上分成多个 partition，**一条消息只落其中一个 partition**。
- 关键结论：**同一个 key 的消息进同一个 partition，partition 内有序**。
- 所以"有序"是有条件的：**只在同 key 内有序，跨 key 全局无序**。
- 充值场景落点：key 选 `user_id`，保证"同一用户的充值事件"按序消费；不同用户乱序无妨。

### 2. Offset（偏移量）

- 每条消息在 partition 内有单调递增编号，消费者记录"消费到哪了"。
- 关键结论：**offset 由消费者自己维护**，不是 broker 替你记账。
- 所以"消费成功"与"提交 offset"是两件事——引出"至少一次"重复问题。

### 3. 消费者组（Consumer Group）

- 一个 group 内多实例**分摊** partition，**一条消息同组内只被一个实例消费**。
- 关键结论：**实例数不能超过 partition 数**，多出的实例空转。
- group 只保证"组内不重复"，不保证"重启/rebalance 后不重复"。

### 4. Rebalance（再平衡）

- 消费者加入/离开/宕机时 partition 重新分配。
- 关键结论：**rebalance 期间短暂停消费**，且**已消费未提交 offset 的消息会被重新投递**。
- 直接推出：**重复消费是常态，不是异常**。

> **一句话总结**：分区内有序 → 用 key 保序；offset 自己维护 → 消费与提交分离；组内分摊 → 扩缩容有上限；rebalance 会重投 → 重复必然发生。

---

## 二、核心推理："至少一次"意味着一定会重复

本单元 🔴 P1 重点，必须能脱稿讲。

**"至少一次（at-least-once）"定义**：消息不会丢，但可能被投递多次。

原因：消费者"处理消息"和"提交 offset"是两个独立动作，任何时序组合都会出问题：

| 时序 | 结果 |
|---|---|
| 先处理成功，再提交 offset | 提交前宕机 → 重启后重投 → **重复消费** |
| 先提交 offset，再处理 | 处理前宕机 → 消息已标记消费 → **消息丢失** |

Kafka 默认"先处理后提交"，选择"不丢但可能重复"。**这个选择是刻意的**——金融系统里丢消息比重复消息严重得多（丢 = 用户充值永不到账；重复 = 幂等挡住即可）。

> **"至少一次"意味着一定会重复，所以幂等不是优化，是前提。**

幂等不是锦上添花，而是"不做幂等，系统就是错的"。这就是 W3 Day 5-6 要建 `consumer_biz` 幂等表的原因。

---

## 三、两个"不能"：为什么必须 Outbox

本单元最核心论证，面试高频。

### 不能一：先发消息再落库

```
1. 发 Kafka 消息："用户 A 充值 100 USDT"
2. 写数据库：充值记录
```

**问题**：第 2 步数据库事务回滚（唯一键冲突、连接超时），消息已发出。

**后果**：下游照着实不存在的充值记录调账本 `post()`，给用户凭空加 100 USDT → **资损**。

### 不能二：先落库再发消息（现有写法）

```
1. 写数据库：充值记录（事务提交）
2. 发 Kafka 消息
```

**问题**：第 1 步成功、第 2 步前进程挂了（OOM、发布、网络断），消息**永久丢失**。

**后果**：链上已确认、库里也有记录，但下游账本永远收不到通知 → **用户的钱永远不到账**。

> 现有 `DepositRoutingServiceImpl` 用 Redis `PUBLISH`，本质是"先落库再发布"，有 `notify_status` 可观测，但**没解决"发布前挂掉"的窗口**。

### 结论：业务写入与消息写入必须同事务

```sql
BEGIN;
  INSERT INTO deposit_record (...);      -- 业务表
  INSERT INTO outbox_message (...);      -- 消息也进同一个库
COMMIT;
```

- 两个写入在**同一个本地事务**里，要么都成功、要么都失败——**没有中间态**。
- 消息**不直接发 Kafka**，先落 `outbox_message` 表。
- 独立投递器扫表、发 Kafka、发成功改 `status='SENT'`。
- 投递器"发成功但改状态前"挂了 → 重启后**再发一次** → 下游幂等表 `consumer_biz` 挡住 → "至少一次 + 幂等"闭环。

**一句话**：Outbox 把"分布式事务问题"降级成"本地事务 + 至少一次投递 + 消费幂等"三个可解问题。

---

## 四、outbox_message 表设计

```sql
CREATE TABLE outbox_message (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  biz_type     VARCHAR(32)  NOT NULL,   -- DEPOSIT / WITHDRAW / MATCH ...
  biz_no       VARCHAR(64)  NOT NULL,   -- 业务单号，用于消费端幂等
  payload      JSON         NOT NULL,   -- 消息体
  status       VARCHAR(16)  NOT NULL DEFAULT 'NEW',  -- NEW / SENT / FAILED
  retry_count  INT          NOT NULL DEFAULT 0,
  next_retry_at DATETIME    NOT NULL DEFAULT NOW(),
  created_at   DATETIME     NOT NULL DEFAULT NOW(),
  INDEX idx_status_next (status, next_retry_at)  -- 投递器扫描用
);
```

### 每个字段的"为什么"

| 字段 | 为什么必须有 |
|---|---|
| `biz_type` + `biz_no` | 消费端幂等依据。下游按 `(biz_type, biz_no)` 判重，重复投递直接跳过。 |
| `payload` | 消息体。用 JSON 而非引用业务表，避免投递时业务表已变更。 |
| `status` | `NEW` 待投递、`SENT` 已投递、`FAILED` 重试超限。**投递结果可观测**。 |
| `retry_count` | 控制重试次数（W4 的 8 次上限落在这）。 |
| `next_retry_at` | 退避重试落点。没有它失败消息立刻重投 → 重试风暴（W4 Day 1-2 主题）。 |
| `idx_status_next` | 投递器扫描索引。**没有它投递器就是全表扫**——计划避坑点名。 |

### 为什么 biz_no 单独列，不塞进 payload

消费端幂等表要按 `biz_no` 建唯一键（`consumer_biz` 主键）。塞进 JSON 里没法建索引、没法做 `INSERT ... 命中主键冲突`的幂等判断。

### 与现有代码的关系

现有 `DepositRoutingServiceImpl` 是"先落库（`deposit_record`）再 Redis `PUBLISH`"。改造后：

```sql
BEGIN;
  INSERT INTO deposit_record (...);
  INSERT INTO outbox_message (biz_type='DEPOSIT', biz_no=deposit_no, payload=..., status='NEW');
COMMIT;
```

`notify_status` 保留，作为**投递器回填**的观测字段，与 `outbox_message.status` 形成"业务侧 + 投递侧"双层可观测。

---

## 五、Day 3-4 会接上的两个细节

本 Day 输出只是 DDL + 概念卡，但要知道后面两步怎么用这张表，否则建表会建错。

### 1. 投递器扫描语句

```sql
SELECT * FROM outbox_message
WHERE status='NEW' AND next_retry_at <= NOW()
ORDER BY id LIMIT 200
FOR UPDATE SKIP LOCKED;
```

`FOR UPDATE SKIP LOCKED` 是**多实例防抢同一条**的关键。索引 `idx_status_next` 就是为这条语句建的。

### 2. 幂等表

```sql
CREATE TABLE consumer_biz (
  biz_no VARCHAR(64) PRIMARY KEY,
  consumed_at DATETIME NOT NULL
);
```

消费者处理前先 `INSERT`，命中主键冲突 → 直接 ack 跳过。挡的是"投递器发成功但改状态前挂了导致的重复投递"。

---

## 六、验收标准

> **验收**：停掉投递器，业务照常落库（证明解耦）；手插两条同 `biz_no`，只落一次账。

两个动作：

1. **停投递器 → 业务照常落库**：证明业务表和 outbox 表同事务、业务不依赖 Kafka 可用性——"解耦"的证据。
2. **手插两条同 `biz_no` → 只落一次账**：证明消费端幂等生效——"至少一次"闭环的证据。

两个动作都过，Day 1-2 的推理才算落地。

---

## 七、面试口述版（约 2 分钟）

> "充值确认后要通知账务系统入账。最早是落库后直接发 MQ，但有两个坑：先发消息后落库，库回滚了下游会照不存在的记录结算；先落库后发消息，进程在发之前挂了消息就永久丢。所以我们用 Outbox：业务记录和 outbox 消息写在同一个本地事务里，两个要么都成要么都败。然后一个独立投递器扫 outbox 表发 Kafka，发成功改状态。投递器也可能发成功后改状态前挂，所以是'至少一次'，重复必然发生——所以消费端必须幂等，按 `biz_no` 建唯一键挡住。这套下来，分布式事务问题就降级成本地事务 + 至少一次投递 + 消费幂等三个可解的问题。"

覆盖：问题、两种错误写法、Outbox 机制、重复来源、幂等落点、最终结论。

---

**一句话收束**：这一天不是学 Kafka，是学**"为什么消息可靠性必须从数据库事务里长出来"**。Kafka 四件事让你知道"重复从哪来"，Outbox 让你知道"怎么把不确定性关进笼子"，表设计把这两件事落成可执行的东西。

---

# 附：FOR UPDATE SKIP LOCKED 详解

## 一、拆开看三个词

### 1. FOR UPDATE

- `SELECT` 时给查到的行加**排他锁（X 锁）**，直到事务提交/回滚才释放。
- 作用：锁住的行别人不能改、不能再 `FOR UPDATE`（会阻塞）。
- 用途：**"读出来并占住，防止别人同时处理"**。

```sql
SELECT * FROM outbox_message WHERE status='NEW' LIMIT 1 FOR UPDATE;
```

两个事务同时执行，第二个**阻塞等待**第一个提交。

### 2. SKIP LOCKED

- 遇到**已被别人锁住的行，直接跳过，不等待**。
- 不加：第二个事务阻塞等第一个 → 吞吐低、可能死锁/超时。
- 加了：第二个事务立刻拿到"剩下没被锁的"行 → 并行处理。

### 3. 合起来

> **"查出来并锁住这些行；某些行已被别人锁了就跳过它们，别等我。"**

---

## 二、为什么投递器需要它

场景：**部署 3 个投递器实例**同时扫表。

### 不加 SKIP LOCKED

```
实例 A: 查出 id 1~200，锁住 → 逐条发 Kafka
实例 B: 查出 id 1~200，全被 A 锁住 → 阻塞等待
实例 C: 同样阻塞
```

后果：3 实例**串行化**、并行度归零；等待超时报 `Lock wait timeout exceeded`；A 处理 200 条要 30 秒则 B/C 全等，延迟雪崩。

### 加了 SKIP LOCKED

```
实例 A: 锁住 1~200，处理
实例 B: 发现全被锁 → 跳过 → 拿 201~400
实例 C: 拿 401~600
```

三实例**真正并行**，各处理各的批次。这就是"多实例防抢同一条"的实现方式。

---

## 三、关键机制：锁在事务内

```sql
BEGIN;
  SELECT * FROM outbox_message
  WHERE status='NEW' LIMIT 200
  FOR UPDATE SKIP LOCKED;        -- 此刻锁住这 200 行
  -- 逐条发 Kafka，发成功 UPDATE status='SENT'
  UPDATE outbox_message SET status='SENT' WHERE id IN (...);
COMMIT;                           -- 提交后锁释放
```

- 锁的**持有期 = 整个事务**，不是 SELECT 结束就放。
- 投递器必须**在同一事务里**完成"SELECT FOR UPDATE → 发消息 → UPDATE status"，否则锁提前释放，别的实例又能抢到同一批。
- 注意：发 Kafka 是外部 IO，包在 DB 事务里会**延长事务、占用连接**。生产折中：事务内只做"SELECT FOR UPDATE SKIP LOCKED + 标记 status='SENDING'"，发 Kafka 放事务外，发完再改 `SENT`。

---

## 四、和普通 FOR UPDATE 的对比

| 写法 | 遇到被锁的行 | 适合场景 |
|---|---|---|
| `FOR UPDATE` | **阻塞等待** | 单实例，或"必须按顺序等前一个处理完" |
| `FOR UPDATE SKIP LOCKED` | **直接跳过** | **多实例抢任务队列**（投递器、扫块、对账分片） |
| `FOR UPDATE NOWAIT` | **立刻报错** | 宁可失败也不等（快速失败） |

> 对比：W17 "扫块/补拉/追踪三处都用 Redisson tryLock" 是**分布式锁**方案；`SKIP LOCKED` 是**数据库行锁**方案。任务在 DB 表里 → `SKIP LOCKED` 最自然；任务不在 DB（如扫块游标） → 分布式锁。

---

## 五、坑：LIMIT 与 SKIP LOCKED 的顺序

```sql
SELECT * FROM outbox_message
WHERE status='NEW' AND next_retry_at <= NOW()
ORDER BY id
LIMIT 200
FOR UPDATE SKIP LOCKED;
```

语义：**先按条件+排序找候选行，再在候选里跳过被锁的，取前 200 条加锁**。被跳过的部分不占 `LIMIT` 名额——实例 B 拿到的是"除 A 锁住的之外的前 200 条"。若未锁定行不足 200 条，实例 B 拿不满，正常，下一轮再扫。

---

## 六、面试口述版（30 秒）

> "投递器多实例部署，同时扫 outbox 表。如果只用 `FOR UPDATE`，第一个实例锁住一批行，其它实例全阻塞等待，并行度归零、还会锁超时。加 `SKIP LOCKED` 后，实例遇到已被锁的行直接跳过，去拿下一批没被锁的，三个实例各处理各的批次、真正并行。锁的持有期是整个事务，所以投递器要在同一事务里完成'查 + 发 + 改状态'。"

---

**一句话收束**：`FOR UPDATE SKIP LOCKED` = "查出并锁住我能处理的，被别人锁住的我不管、跳过"。它是**数据库层面的任务队列抢占**，是投递器多实例不重复、不阻塞的关键语法。

---

# 附：Outbox 名称与本质详解

## 一、字面意思：发件箱

Outbox = "发出邮箱"，对应 **Inbox（收件箱）**。

想象写信：写好信 → **先放进发件箱（outbox）**，不是直接塞邮筒 → 邮递员定期取信寄出 → 寄成功标记"已寄出"，寄失败留箱里下次再寄。

**关键特征**：信"写好"和"寄出"是两个独立动作，中间隔着一个"发件箱"作为缓冲。

| 生活 | 数据库 |
|---|---|
| 写信 | 写业务数据 |
| 放进发件箱 | 往 `outbox_message` 表插一条记录 |
| 邮递员取信寄出 | 投递器扫表、发 Kafka |
| 标记已寄出 | `status` 改成 `SENT` |

---

## 二、解决什么问题：业务写入与消息发送的原子性

### 问题本质

一次充值确认要做两件事：

1. **写业务库**：`deposit_record` 落一条"用户 A 充值 100 USDT"
2. **发消息**：通知账务系统"给 A 加 100"

两件事**必须同时成功或同时失败**。但业务库是 MySQL、消息是 Kafka，**两个不同系统**，没有"跨 MySQL 和 Kafka 的本地事务"。

两难：

```
先写库后发消息 → 发之前挂了 → 消息永久丢失（用户钱不到账）
先发消息后写库 → 库回滚了   → 下游照不存在的记录结算（资损）
```

### Outbox 的解法：把"两个系统"变成"一个系统"

**不要直接发 Kafka，把"要发的消息"也写进 MySQL。**

```sql
BEGIN;
  INSERT INTO deposit_record (...);      -- 业务数据
  INSERT INTO outbox_message (...);      -- 消息也进 MySQL
COMMIT;
```

两件事都在 **MySQL 同一个本地事务**里——**要么都成，要么都败，没有中间态**。

"发 Kafka"从业务事务里剥离，交给独立后台投递器：

```
投递器循环：
  SELECT ... FROM outbox_message WHERE status='NEW' FOR UPDATE SKIP LOCKED;
  发 Kafka;
  UPDATE outbox_message SET status='SENT';
```

投递器挂了、Kafka 挂了都不影响业务写入；投递器重启后接着扫表，消息最终一定发出。

**一句话**：Outbox 把"跨系统分布式事务"降级成"本地事务 + 一个可重试的投递器"。

---

## 三、为什么这个模式"正确"

正确性来自朴素事实：**数据库本地事务是可靠的，而"写库 + 发消息"跨系统是不可靠的**。

Outbox 把不可靠的跨系统操作，压缩进可靠的单库事务，再把"不可靠的部分"（发消息）隔离成一个可重试、可观测、可失败的独立环节。

这就是计划把 Outbox 列成 🔴 P1 的原因：

- 不懂它，会在业务事务里直接 `kafkaTemplate.send()`——发消息失败就丢消息、库回滚就多发消息。
- 懂它，就知道**消息必须先落库**，发消息是投递器职责，业务代码只管写 outbox 表。

---

## 四、为什么一定会重复（以及为什么没关系）

投递器流程：

```
发 Kafka → 改 status='SENT'
```

"发成功、改状态前"投递器挂了，重启后还是 `status='NEW'`，会**再发一次**。所以 Outbox 天然是**至少一次**投递。

**重复不是 bug，是设计的必然。** 解法不是"想办法不重复"（做不到），而是**消费端幂等**：

```sql
CREATE TABLE consumer_biz (biz_no VARCHAR(64) PRIMARY KEY, ...);
-- 消费前 INSERT，命中主键冲突 → 处理过了 → 直接跳过
```

Outbox 负责"不丢"，幂等表负责"重复无害"。

---

## 五、和现有代码的对照

改造前代码 30% 完成、标 🟡（历史对照）：

> 改造前已有：先落库后发布（`DepositRoutingServiceImpl`）、`notify_status` INIT/SENT/FAILED 可观测
> 改造前缺：`outbox_message` 表与投递器、Kafka、消费幂等表

改造前写法：

```
写 deposit_record（提交）→ Redis PUBLISH → 改 notify_status
```

**不是 Outbox**，因为：

- 消息（Redis PUBLISH）**不在业务事务里**——业务提交后、PUBLISH 前挂掉，消息就丢了。
- 没有 outbox 表作为"消息持久化落脚点"，没有投递器补发。
- `notify_status` 只是"结果可观测"，不是"消息可重放"。

改造后：

```sql
BEGIN;
  INSERT deposit_record (...);
  INSERT outbox_message (..., status='NEW');
COMMIT;
-- 投递器异步扫表发 Redis 频道或 Kafka（transport 切换），成功后回填 notify_status='SENT'
```

差别：**消息从"一次性网络调用"变成"数据库里一行持久记录"**。有持久记录，才有"丢了能补、挂了能重"的余地。

> **当前状态（2026-11-16）**：改造已完成——`outbox_message` + `OutboxDispatcher`（SKIP LOCKED + 指数退避）+
> `consumer_biz` 幂等消费者 + Kafka 消费者；`web3.outbox.transport=redis`（默认）/`kafka` 切换通道。
> 代码位置与关键日志见 README 5.16 与 `file/第三阶段/Day5-6消费者与本地跑通.md`。

---

## 六、面试口述版（30 秒）

> "Outbox 就是发件箱模式：要发的消息不直接扔给 MQ，而是先和业务数据写在同一个本地事务里，落到 outbox 表。这样业务和消息同生共死，没有'库成了消息丢了'或'消息发了库回滚了'的中间态。然后一个独立投递器扫 outbox 表发 Kafka，发成功改状态。投递器可能在发成功后改状态前挂，所以是至少一次，消费端用 biz_no 唯一键做幂等。本质是把跨系统的分布式事务，降级成本地事务加一个可重试的投递器。"

---

**一句话收束**：Outbox = **"消息先落库、再异步发"**。名字来自"发件箱"，本质是**用数据库的本地事务，给"业务写入 + 消息发送"这对跨系统操作提供原子性保证**，代价是至少一次投递，由消费端幂等兜底。
```
