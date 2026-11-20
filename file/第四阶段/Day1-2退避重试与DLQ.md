# Day 1-2：退避重试与 DLQ

> 对应 18 周计划第 4 周 Day 1-2
> 目标：投递失败按指数退避 + jitter 重试；超过上限写 `dlq_message` 并保留原消息
> 产出：`RetryPolicy` + Outbox 失败状态机 + `dlq_message` 表/Mapper + 单元测试

---

## 一、为什么不能立即 requeue

### 1.1 重试风暴

1000 条消息同时失败，如果立即重新入队，就是 1000 条消息在同一秒打回刚缓过来的数据库/下游，
把一次短故障放大成持续故障。指数退避把重试摊开到更长的时间窗，让下游有机会恢复。

### 1.2 为什么要 jitter

如果所有实例、所有消息都使用相同的 `base` 和相同的失败时刻，退避时间会完全对齐，
等于制造一个周期性的定时脉冲。乘以 `[0.5, 1.5)` 的随机系数后，重试请求在时间轴上被打散。

### 1.3 cap 在 jitter 之前生效

先封顶再抖动，单次最大等待为 `1.5 * cap`（默认 7.5 分钟），
不会出现“指数爆炸后 jitter 再放大”的不可控延迟。

---

## 二、RetryPolicy 实现

### 2.1 公式与默认参数

代码：`src/main/java/com/ray/web3/util/RetryPolicy.java`
装配：`src/main/java/com/ray/web3/config/RetryConfig.java`

```text
delay = min(base * 2^(attempts-1), cap) * (0.5 + random())
```

| 参数 | 默认值 | 说明 |
|---|---|---|
| `baseSeconds` | 1s | 退避基数；小于 1 按 1 处理 |
| `capSeconds` | 300s（5min） | 指数增长上限；小于 base 按 base 处理 |
| `maxAttempts` | 8 | 累计失败次数达到即写死信，不再自动重试 |
| `random` | `[0,1)` 线程本地随机 | 测试可注入固定值 |

`RetryPolicy` 是纯函数（除随机源外）：不碰数据库、不 sleep、不依赖 Spring，
便于单测和在不同投递器间复用。

### 2.2 第 1-8 次失败的实际等待

| 失败次数 | 指数值 | jitter 后范围 | 说明 |
|---:|---:|---:|---|
| 1 | 1s | 1~2s | 第 1 次失败 |
| 2 | 2s | 1~3s | |
| 3 | 4s | 2~6s | |
| 4 | 8s | 4~12s | |
| 5 | 16s | 8~24s | |
| 6 | 32s | 16~48s | |
| 7 | 64s | 32~96s | 第 8 次尝试前的最后一次等待 |
| 8 | 128s | 64~192s | **终态**：写 DLQ + FAILED，不再自动投递 |

> 注意：默认 `maxAttempts=8` 时，指数最大只到 128s，`cap=300s` 实际不会触发。
> 只有调大 `max-retries` 后 cap 才生效；此时单次最大等待是 `1.5 * cap = 450s`。
> 第 8 次失败算出的 128s（jitter 64~192s）仍会写入 `next_retry_at`，
> 但状态已经是 `FAILED`，不会被 `status='NEW'` 的扫描条件再次捞起。

### 2.3 单测

`src/test/java/com/ray/web3/util/RetryPolicyTest.java`，7 个用例：

- 指数退避按基数翻倍并封顶（含 `Integer.MAX_VALUE` 不溢出）；
- jitter 落在 `[0.5d, 1.5d]`（每个 attempts 跑 200 次）；
- 同一重试次数跑 100 次不全相同（证明随机源生效）；
- 固定随机源时延迟等于指数值；
- 达到最大次数判定耗尽；
- 非法参数归一化；
- 默认参数为 1s 基数 / 5min 上限 / 8 次。

---

## 三、失败状态机

```text
                 publish 成功
   NEW ───────────────────────────────► SENT
    │
    │ publish 失败，attempts < maxAttempts
    ├──────────────────────────────────► NEW
    │     retry_count + 1
    │     next_retry_at = NOW(3) + delay
    │
    │ publish 失败，attempts >= maxAttempts
    └──────────────────────────────────► FAILED
          + dlq_message(status=OPEN)
          + 原 outbox_message 保留（不删除）
```

### 3.1 OutboxDispatcher

`src/main/java/com/ray/web3/service/impl/OutboxDispatcher.java`

```text
dispatchBatch()  @Transactional(rollbackFor = Exception.class)
  └─ selectPendingBatch(batchSize)
       WHERE status='NEW' AND next_retry_at <= NOW(3)
       ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED
  └─ for each message:
       publish 成功
         └─ markSent(id); deposit_record.notify_status=SENT
       publish 失败
         └─ retryCount = retry_count + 1
            delay = retryPolicy.delaySeconds(retryCount)
            if retryPolicy.isExhausted(retryCount):
                moveToDlq(message, retryCount, cause)
                markFailed(id, delay)
            else:
                markRetry(id, delay)
            deposit_record.notify_status=FAILED
```

关键点：

- **单条失败不抛出**：否则整批回滚，失败计数也丢失。数据库更新本身失败才向上抛，
  让本轮整体回滚、消息保持 `NEW` 等下一轮。
- **行锁在事务内**：`FOR UPDATE SKIP LOCKED` 只在事务期间有效；扫描、发送、改状态在同一事务。
- **原消息不删除**：超限只把 `outbox_message.status` 置 `FAILED`，`dlq_message` 另存一份留档。
- **死信与 FAILED 同事务**：`moveToDlq` + `markFailed` 任一失败整轮回滚，消息仍是 `NEW`，
  不会出现“原消息没了、死信也没写”的丢消息。

### 3.2 SQL 时间口径

`src/main/java/com/ray/web3/mapper/OutboxMessageMapper.java`

| 方法 | SQL 行为 |
|---|---|
| `selectPendingBatch` | `status='NEW' AND next_retry_at <= NOW(3) ... FOR UPDATE SKIP LOCKED` |
| `markRetry` | `retry_count+1`、`next_retry_at=TIMESTAMPADD(SECOND, delay, NOW(3))`、状态保持 `NEW` |
| `markFailed` | 同上，但 `status='FAILED'` |
| `insert` | 不写 `next_retry_at`，由 DB 默认 `CURRENT_TIMESTAMP(3)` 填充 |

时间计算全部放在数据库侧（`NOW(3)` / `TIMESTAMPADD`），避免 JVM 与 MySQL 时区不一致导致
“消息要等 8 小时才重试”。扫描索引为 `outbox_message.idx_status_next(status, next_retry_at)`。

### 3.3 关联实现：Kafka 分区键

同一用户的事件需要按序消费，因此 `outbox_message` 增加 `partition_key`：

- `DepositRoutingServiceImpl`：USER 充值用 `userId`，HOT 钱包无归属用户时回退 `depositNo`；
- `KafkaOutboxPublisher`：优先使用 `partition_key`，为空回退 `bizNo` 作为 Kafka key；
- `KafkaOutboxPublisherTest`：3 个用例覆盖 userId、回退 bizNo、broker 失败。

---

## 四、DLQ 数据模型

### 4.1 表结构

`sql/schema.sql` 中的 `dlq_message`：

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | `BIGINT UNSIGNED` | 主键 |
| `source_table` | `VARCHAR(32)` | 来源表，当前固定 `outbox_message` |
| `source_id` | `BIGINT UNSIGNED` | 原消息 id |
| `biz_type` / `biz_no` | `VARCHAR(32/128)` | 业务类型与业务单号 |
| `partition_key` | `VARCHAR(64)` | 原 Kafka 分区键 |
| `payload` | `TEXT` | 原消息体快照 |
| `retry_count` | `INT` | 转移时的累计失败次数 |
| `error_message` | `VARCHAR(512)` | 最后一次失败原因（代码截断到 512） |
| `status` | `VARCHAR(16)` | `OPEN` / `HANDLED` / `IGNORED`，默认 `OPEN` |
| `created_at` | `DATETIME(3)` | 进入死信时间 |

索引：

- `UNIQUE KEY uk_source(source_table, source_id)`：同一条原消息最多一条死信；
- `KEY idx_biz_no(biz_no)`：人工按业务单号找回；
- `KEY idx_status_created(status, created_at)`：按状态/时间筛选待处理死信。

### 4.2 写入逻辑

`OutboxDispatcher.moveToDlq`：

1. 从原 `outbox_message` 复制 `source_table/source_id/biz_type/biz_no/partition_key/payload/retry_count`；
2. `error_message` 截断到 512 字符；
3. `status=OPEN`；
4. `DlqMessageMapper.insertIgnore`：命中 `uk_source` 静默跳过，重复处理不会堆多条死信；
5. `outbox.dlq` Counter +1（Prometheus 侧通常为 `outbox_dlq_total`）。

同时 `OutboxMessageMapper.markFailed` 把原消息置 `FAILED`，**不删除**。

### 4.3 当前范围

- **只做落库与可观测**：没有 DLQ 重放接口/控制台，`HANDLED`/`IGNORED` 常量已预留但未被代码使用，
  表也没有 `handled_at` / `handled_by` 字段；
- **告警**：当前是 ERROR 日志 + `outbox.dlq` Counter，未配置 Prometheus 告警规则/Alertmanager 通知；
- **DLQ 不是 Kafka dead-letter topic**：它是 MySQL 表，便于按业务单号查询和对账。

---

## 五、SQL 与关键日志

### 5.1 验收 SQL

```sql
-- 1) 退避中的消息：已失败但未达上限，等待下一次扫描
SELECT id, biz_no, status, retry_count, next_retry_at
FROM outbox_message
WHERE status = 'NEW' AND retry_count > 0
ORDER BY id DESC;

-- 2) 终态失败消息：原消息保留（不删除）
SELECT id, biz_no, status, retry_count, next_retry_at
FROM outbox_message
WHERE status = 'FAILED'
ORDER BY id DESC;

-- 3) 死信与原始消息一一对应
SELECT d.id, d.source_id, d.biz_no, d.retry_count, d.status,
       d.error_message, d.created_at, o.status AS outbox_status
FROM dlq_message d
JOIN outbox_message o ON o.id = d.source_id
WHERE d.source_table = 'outbox_message'
ORDER BY d.id DESC;

-- 4) 不应存在孤儿死信
SELECT d.*
FROM dlq_message d
LEFT JOIN outbox_message o ON o.id = d.source_id
WHERE o.id IS NULL;
```

预期：

- 第 1 类：`retry_count` 递增，`next_retry_at` 在未来；
- 第 2 类：`status='FAILED'`，原消息仍在；
- 第 3 类：每条死信对应一条 `FAILED` 原消息；
- 第 4 类：返回空集。

### 5.2 关键日志

```text
[outbox] 本轮扫描 3 条，投递成功 0 条
[outbox] 投递失败，1s 后重试（含抖动） id=1 bizType=DEPOSIT bizNo=DP... retry=1/8: broker down
[outbox] 投递失败，2s 后重试（含抖动） id=1 bizType=DEPOSIT bizNo=DP... retry=2/8: broker down
...
[outbox][dlq] 投递失败达到上限(8)，已写死信并保留原消息 FAILED
              id=1 bizType=DEPOSIT bizNo=DP...: broker down
```

日志里的 `{n}s` 是本次实际等待秒数（已含 jitter），不是指数原值。

---

## 六、配置

`src/main/resources/application.yml` 的 `web3.outbox.*`：

| 配置 | 默认值 | 说明 |
|---|---|---|
| `enabled` | `true` | 投递器总开关 |
| `transport` | `kafka` | 当前 yml 默认 Kafka；`redis` 为旧通道 |
| `dispatch-interval-ms` | 1000 | 投递轮询间隔 |
| `batch-size` | 200 | 单轮扫描条数 |
| `max-retries` | 8 | 达到后 `FAILED` + DLQ |
| `retry-base-seconds` | 1 | 退避基数 |
| `retry-max-seconds` | 300 | 退避封顶（默认 8 次时实际不会触发） |
| `kafka.send-timeout-seconds` | 5 | 等待 broker ack 的超时；超时按失败退避 |

> `OutboxProperties` 字段级默认值是 `redis`，但当前 `application.yml` 覆盖为 `kafka`；
> 以 yml/环境变量 `WEB3_OUTBOX_TRANSPORT` 为准。没有单独的 DLQ 配置项——
> 死信是 MySQL 表，不是 Kafka topic。

---

## 七、验证

### 7.1 离线单测

```bash
JAVA_HOME=<JDK21> mvn -Dtest='RetryPolicyTest,OutboxDispatcherTest,KafkaOutboxPublisherTest,\
DepositOutboxConsumerTest,DepositRoutingOutboxTransactionTest,\
DepositRoutingServiceImplTest,RedisOutboxPublisherTest' test
```

本次运行结果（2026-11-20）：**34 个用例全部通过**。

| 测试类 | 用例数 | 覆盖点 |
|---|---:|---|
| `RetryPolicyTest` | 7 | 指数/封顶/jitter/耗尽/参数归一化 |
| `OutboxDispatcherTest` | 6 | SENT、退避重试、超限 FAILED+DLQ、事务 |
| `KafkaOutboxPublisherTest` | 3 | userId 分区键、回退 bizNo、broker 失败 |
| `DepositOutboxConsumerTest` | 4 | 成功 ack、重复 ack、rebalance、失败不 ack |
| `DepositRoutingOutboxTransactionTest` | 2 | 业务 + Outbox 同事务/回滚 |
| `DepositRoutingServiceImplTest` | 10 | 充值路由、partitionKey |
| `RedisOutboxPublisherTest` | 2 | Redis 通道、未知类型失败 |

### 7.2 手工制造失败

任选一种：

1. 停掉当前投递通道（Kafka/Redis），让 `publish` 抛异常；
2. 把 `WEB3_OUTBOX_MAX_RETRIES` 调成 `1`、`WEB3_OUTBOX_RETRY_BASE_SECONDS=1`，快速触发终态；
3. 用 `OutboxDispatcherTest` 的 Mock 场景验证状态机，不依赖真实 broker。

观察 `outbox_message.retry_count`、`next_retry_at`、`status`，以及 `dlq_message` 是否出现 `OPEN` 记录。

### 7.3 预期日志

- 未达上限：`[outbox] 投递失败，{n}s 后重试（含抖动） ... retry={x}/8`；
- 达到上限：`[outbox][dlq] 投递失败达到上限(8)，已写死信并保留原消息 FAILED ...`。

---

## 八、验收对照

| 计划要求 | 状态 | 证据 |
|---|---|---|
| `delay = min(base*2^retry, cap) * (0.5+random())`，base=1s、cap=5min | ✅ | `RetryPolicy.delaySeconds` / `exponentialSeconds` |
| 不能立即 requeue（重试风暴） | ✅ | `RetryPolicy` 类注释 + 指数退避 |
| jitter 打散定时脉冲 | ✅ | `[0.5, 1.5)` 随机系数；`RetryPolicyTest` |
| 最大 8 次 | ✅ | `DEFAULT_MAX_ATTEMPTS=8` / `OutboxProperties.maxRetries` |
| 超限写 `dlq_message` + 告警 | ✅ 部分 | 表/Mapper/Counter/ERROR 日志已实现；未配置告警规则 |
| 不删原消息 | ✅ | `markFailed` 只改状态，无 delete 方法 |
| `RetryPolicy` + 单测 | ✅ | 7 个用例，34 个相关用例全绿 |
| DLQ 重放/控制台 | ⬜ | 当前只有 `insertIgnore`，无查询/重放/标记处理 |
| 消费端重试策略 + DLQ | ⬜ | 本轮只做 Outbox 投递侧；消费端仍为“失败不 ack” |
| 三方对账 / RPC 主备 | ⬜ | 属 Day 5-6 / 第 4 周余量，不在 Day 1-2 |

---

## 九、已知限制与后续

1. **DLQ 只落库，不支持重放**：`HANDLED`/`IGNORED` 已预留但未使用，也没有 `handled_at`/`handled_by`；
   需要人工按 `biz_no` 找回业务记录处理。
2. **Outbox 重试 ≠ 消费端重试**：本 Day 的退避/DLQ 只覆盖“投递器发不出去”；
   消费者侧没有 `@RetryableTopic`/`DefaultErrorHandler`/消费 DLQ。
3. **`MANUAL_IMMEDIATE` 的中批失败风险**：同批第 N 条失败不 ack、第 N+1 条成功并 ack 时，
   committed offset 可能越过失败那条，导致它不一定被重投。生产上应改为逐条提交、
   失败时暂停分区，或给消费者加真正的重试/DLQ；当前只作为已知限制记录。
4. **告警只到指标/日志层**：`outbox.dlq` Counter + ERROR 日志，没有 Prometheus 告警规则/通知渠道。
5. **cap 与 8 次的交互**：默认 8 次时 cap 不会触发；最大实际等待是第 7 次失败后的 64s
   （jitter 后 32~96s），不是 5 分钟。
6. **Day 3-4 的提币自动解冻、lag 指标**见
   [`Day3-4失败补偿与杀消费者演练.md`](Day3-4失败补偿与杀消费者演练.md)，不要与本篇的 Outbox DLQ 混淆。

---

## 十、代码位置

| 文件 | 职责 |
|---|---|
| `src/main/java/com/ray/web3/util/RetryPolicy.java` | 指数退避 + jitter + 封顶 + 耗尽判定（纯函数） |
| `src/main/java/com/ray/web3/config/RetryConfig.java` | 从 `OutboxProperties` 装配 `RetryPolicy` |
| `src/main/java/com/ray/web3/service/impl/OutboxDispatcher.java` | 失败状态机、`moveToDlq`、`outbox.dlq` Counter |
| `src/main/java/com/ray/web3/mapper/OutboxMessageMapper.java` | `SKIP LOCKED` 扫描、`markRetry`/`markFailed` |
| `src/main/java/com/ray/web3/model/entity/DlqMessage.java` | 死信实体 |
| `src/main/java/com/ray/web3/mapper/DlqMessageMapper.java` | `INSERT IGNORE dlq_message` |
| `src/main/java/com/ray/web3/consts/OutboxConsts.java` | `SourceTable`/`DlqStatus`/`Status` 常量 |
| `sql/schema.sql` | `outbox_message` / `dlq_message` DDL 与索引 |
| `src/main/resources/application.yml` | `web3.outbox.*` 配置 |
| `src/test/java/com/ray/web3/util/RetryPolicyTest.java` | 退避策略单测 |
| `src/test/java/com/ray/web3/service/impl/OutboxDispatcherTest.java` | 状态机与 DLQ 单测 |

---

## 十一、自测

**① 为什么不能失败后立即重新入队？**
会形成重试风暴：同一时刻失败的消息在同一秒全部重投，把刚恢复的下游再次打挂。
指数退避把重试摊开，jitter 打散多实例的同步脉冲。

**② 第 3 次失败应该等多久？**
默认 base=1s，第 3 次指数值为 4s，jitter 后约 2~6s。

**③ 为什么要写 DLQ 而不是直接丢弃？**
DLQ 保留失败现场（业务单号、payload、错误原因、重试次数），可人工按 `biz_no` 找回并补偿；
原 `outbox_message` 同时保留为 `FAILED`，两边可互相核对。

**④ 原消息会不会被删除？**
不会。`OutboxDispatcher` 只调用 `markFailed` 改状态，Mapper 没有 delete 方法。

**⑤ 死信重复写会怎样？**
`dlq_message.uk_source(source_table, source_id)` + `INSERT IGNORE`：同一条原消息最多一条死信。

**⑥ DLQ 写入失败怎么办？**
死信写入和原消息 `FAILED` 在同一个事务里；任一失败整轮回滚，原消息保持 `NEW`，
下一轮继续重试，不会丢消息。

**⑦ 默认 8 次时 cap=5min 会生效吗？**
不会。指数最大到 128s；只有调大 `max-retries` 后 cap 才可能触发。