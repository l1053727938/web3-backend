# 第 3 周：消息可靠性与 Outbox 实现总结

> 日期：2026-11-18  
> 范围：W3 Day 1-7（Outbox 原理、同事务改造、Kafka 消费者、幂等、分区键、验收）  
> 状态：🟢 已完成；单测与本地 MySQL 端到端均已验证

---

## 一、目标与结论

**目标**：把充值链路的“先落库再发消息”改成 **业务表 + outbox_message 同一本地事务**，再异步投递到 Redis/Kafka；消费端做到“至少一次投递 + 幂等入账”。

**结论**：

- 业务与消息同生共死：业务回滚则 outbox 不存在；业务提交则消息一定可被投递器扫到。
- 投递语义是 **at-least-once**：重复不可避免，由 `consumer_biz` + `ledger_entry.uk_biz` 两层幂等兜底。
- 消费端手动 ack：只有入账成功才提交 offset；失败不 ack，重启/rebalance 后重投。
- 同一用户的充值事件通过 `partition_key=userId` 进入同一 Kafka partition，保证用户级顺序。
- 已完成端到端验证：5 笔充值 → `outbox_message SENT=5` → `consumer_biz=5` → `ledger_entry(DEPOSIT)=5`，账户余额一致。

---

## 二、端到端链路

```text
Transfer 事件（WS 实时订阅 / 历史补拉）
  │
  ▼
DepositRoutingServiceImpl.route()        ← 同一个本地事务
  ├─ depositWalletService.match(toAddress)     命中 HOT/USER 充值地址
  ├─ INSERT IGNORE deposit_record              幂等键 (chain_id, tx_hash, log_index)
  ├─ fillUserIdsByAddress()                    按收款地址批量反查 userId，必要时回写 deposit_record
  └─ INSERT outbox_message                     status=NEW, partition_key=userId
  └─ 事务提交
  │
  ▼
OutboxDispatchScheduler（默认每 1s，web3.outbox.enabled 控制）
  │
  ▼
OutboxDispatcher.dispatchBatch()
  ├─ SELECT ... WHERE status='NEW' AND next_retry_at<=NOW(3)
  │         ORDER BY id LIMIT 200 FOR UPDATE SKIP LOCKED
  ├─ OutboxPublisher.publish(message)          Redis 频道 / Kafka topic
  ├─ 成功：status=SENT + deposit_record.notify_status=SENT
  └─ 失败：retry_count+1 + 指数退避；超过 8 次 status=FAILED
  │
  ▼
Kafka topic: deposit-outbox（6 分区，key=userId）
  │
  ▼
DepositOutboxConsumer（group=web3-outbox，manual_immediate）
  ├─ INSERT IGNORE consumer_biz(biz_no)        返回 0 = 重复，直接 ack
  ├─ DepositCreditService.credit()
  │    ├─ 解析 DepositEventVO
  │    ├─ 查用户 SPOT 账户；不存在则 ensureAccount() 懒开户
  │    └─ LedgerService.deposit()              uk_biz(DEPOSIT, depositNo) 幂等
  └─ 成功后 ack.acknowledge()
```

---

## 三、已实现内容

### 3.1 数据表

| 表 | 关键字段 | 作用 |
|---|---|---|
| `outbox_message` | `id / biz_type / biz_no / partition_key / payload / status / retry_count / next_retry_at / created_at` | 业务消息本地消息表；`status` 状态机 NEW → SENT / FAILED |
| `consumer_biz` | `biz_no`（主键） / `consumed_at` | 消费端幂等占位 |
| `deposit_record` | `notify_status`：INIT / SENT / FAILED | 业务侧投递结果可观测 |

索引：

```sql
KEY idx_status_next (status, next_retry_at)
```

投递器只扫 `status='NEW' AND next_retry_at <= NOW(3)`，该索引保证不会全表扫。

`partition_key` 迁移脚本：`sql/migration/20261118_outbox_partition_key.sql`；新库直接由 `sql/schema.sql` 建出。

### 3.2 代码清单

| 组件 | 文件 | 职责与关键点 |
|---|---|---|
| 路由 + Outbox | `DepositRoutingServiceImpl` | 钱包匹配 → `INSERT IGNORE deposit_record` → 按地址反查 userId 回填 → 同事务写 `outbox_message` |
| 定时触发器 | `OutboxDispatchScheduler` | `@Scheduled(fixedDelay=dispatch-interval-ms)`；`web3.outbox.enabled=false` 时整个 Bean 不加载 |
| 投递器 | `OutboxDispatcher` | `SKIP LOCKED` 扫描、逐条投递、SENT/重试/FAILED、回填 `notify_status` |
| 退避算法 | `RetryPolicy` | 第 1 次 base 秒，之后翻倍，封顶 max 秒，再乘 `[0.5, 1.5)` jitter；达上限写 DLQ |
| Redis 通道 | `RedisOutboxPublisher` | `PUBLISH deposit_channel`；`transport=redis` 或未配置时加载（`matchIfMissing=true`） |
| Kafka 通道 | `KafkaOutboxPublisher` | `send(topic, key, payload).get(timeout)`；key=partitionKey，空则回退 bizNo |
| Topic 创建 | `KafkaOutboxConfig` | 仅 `transport=kafka` 时注册 `NewTopic`（6 分区 / 1 副本） |
| 消费者 | `DepositOutboxConsumer` | `@KafkaListener` + 手动 ack；`CommitFailedException` 单独 WARN；失败不 ack |
| 消费入账 | `DepositCreditService` | `consumer_biz` 幂等 → 账户解析/懒开户 → `LedgerService.deposit()` |
| 账本幂等 | `LedgerServiceImpl` | `ledger_entry.uk_biz(biz_type, biz_no)`，重复调用返回原分录、不再动余额 |
| 地址反查 | `DepositWalletService#findUserIdsByAddresses` | 按收款地址批量查 `deposit_wallet`，只返回启用中的 USER 地址 |
| 配置 | `OutboxProperties` / `DepositConsumerProperties` | 投递通道、批量、重试、Kafka；平台负债账户、用户账户类型 |

### 3.3 分区键

- 写 Outbox 时：`partitionKey = userId`（USER 充值）；HOT 钱包没有归属用户，回退 `depositNo`。
- `KafkaOutboxPublisher` 优先使用 `partitionKey`，为空回退 `bizNo`，兼容历史数据。
- Kafka 默认分区器：`toPositive(murmur2(key UTF-8 bytes)) % numPartitions`。
- 同一用户 → 同一 partition；不同用户可并行；分区内有序由 Kafka 保证。

---

## 四、关键配置（当前 application.yml）

### 4.1 Outbox

| 配置 | 默认值 | 说明 |
|---|---|---|
| `web3.outbox.enabled` | `true` | `false` 时停投递器，业务照常落库 |
| `web3.outbox.transport` | `kafka` | 当前默认 kafka；可切 `redis`（注意：README 5.16 旧描述写默认 redis，以配置为准） |
| `web3.outbox.dispatch-interval-ms` | `1000` | 投递轮询间隔 |
| `web3.outbox.batch-size` | `200` | 单轮扫描上限 |
| `web3.outbox.max-retries` | `8` | 达到后置 FAILED，转人工补偿 |
| `web3.outbox.retry-base-seconds` | `5` | 退避基数 |
| `web3.outbox.retry-max-seconds` | `300` | 退避上限 |
| `web3.outbox.kafka.topic` | `deposit-outbox` | 充值通知 topic |
| `web3.outbox.kafka.partitions` | `6` | 需要与已存在 topic 的实际分区数一致；改配置不会自动扩分区 |
| `web3.outbox.kafka.replicas` | `1` | 本地单节点 |
| `web3.outbox.kafka.send-timeout-seconds` | `5` | `send().get()` 等待 broker ack 的超时 |

### 4.2 Kafka 生产者 / 消费者

| 配置 | 值 | 说明 |
|---|---|---|
| `spring.kafka.producer.acks` | `all` | 等待 ISR 确认 |
| `spring.kafka.consumer.group-id` | `web3-outbox` | 消费组 |
| `spring.kafka.consumer.auto-offset-reset` | `earliest` | 新组从最早开始 |
| `spring.kafka.consumer.enable-auto-commit` | `false` | 手动 ack 前提 |
| `spring.kafka.consumer.max-poll-records` | `50` | 控制单批重放量 |
| `spring.kafka.consumer.max-poll-interval` | `300000` | 两次 poll 最大间隔 |
| `spring.kafka.consumer.properties.session.timeout.ms` | `45000` | 断点调试可设 `KAFKA_SESSION_TIMEOUT_MS=300000` |
| `spring.kafka.listener.ack-mode` | `manual_immediate` | `ack.acknowledge()` 后立即提交 offset |

### 4.3 消费入账

| 配置 | 默认值 | 说明 |
|---|---|---|
| `web3.deposit.consumer.platform-liability-account-id` | `900101` | 平台代收负债账户，生产必须覆盖 |
| `web3.deposit.consumer.user-account-type` | `SPOT` | 用户充值入现货账户 |

---

## 五、可靠性语义

### 5.1 崩溃窗口

| 崩溃点 | Kafka 是否有消息 | outbox 状态 | 结果 |
|---|---|---|---|
| publish 前 | 无 | NEW | 重启重投，不丢 |
| publish 成功、markSent 前 | 有 | NEW | 重启重投 → 重复，幂等挡住 |
| markSent 后、事务提交前 | 有 | NEW（回滚） | 重新投递 → 重复，不丢 |
| 事务提交后 | 有 | SENT | 不再重投；依赖 Kafka 自身不丢 |

**顺序不能反**：必须先 `publish` 成功，再 `markSent`。反过来会出现“状态已 SENT、消息没发出”的永久丢失。

### 5.2 重复消息

- 投递器：发送成功但状态回写前宕机、Kafka 重试、rebalance 重投，都会产生重复。
- 消费端第一层：`INSERT IGNORE consumer_biz(biz_no)` 返回 0 → 直接 ack。
- 账本第二层：`ledger_entry.uk_biz(DEPOSIT, deposit_no)` → 返回原分录，不再更新余额。
- 结论：重复是正常现象；不要通过“捕获异常后仍然 ack”来消除重复。

### 5.3 消费失败与 ack

- `credit()` 成功返回后才 `ack.acknowledge()`。
- 任何异常 → 不 ack → offset 不提交 → 重启/rebalance 后重投。
- `CommitFailedException`（rebalance 导致 offset 提交失败）单独捕获并 WARN：入账已成功，消息会重投并按幂等跳过，不是“充值入账失败”。
- 断点调试暂停 JVM 超过 `session.timeout.ms` 会被踢出组；需要长时间断点调试时设置 `KAFKA_SESSION_TIMEOUT_MS=300000`。

### 5.4 Kafka 侧丢失风险

`publish` 成功只代表 broker 已 ack，不代表消费者已处理，也不代表永久保存：

| 风险 | 说明 | 兜底/建议 |
|---|---|---|
| 单副本 | 本地 `replication.factor=1`，删卷/磁盘损坏即丢；outbox 已 SENT 不会补发 | 生产 RF≥3、`min.insync.replicas=2`、`unclean.leader.election.enable=false` |
| retention 到期 | 消费积压超过 `retention.ms`，未消费消息被删除 | 监控 lag 的**时间**，保证最大停机 < retention |
| topic 删除/重建 | 消息直接丢失 | 本地重置时同时清空/重放 outbox |
| 所有 ISR 故障 | acks=all 只保证写入 ISR，不是每笔 fsync | 生产多副本 + 监控 UnderReplicatedPartitions |

> 本地单节点 `acks=all` 等价于只等 leader 自己，**没有冗余**。

### 5.5 超时与重复

`send().get(5s)` 超时**不会取消发送**，producer 可能稍后才写入成功；投递器此时按失败退避重试，会多一条重复消息。
建议让 `send-timeout-seconds` 与 producer 的 `delivery.timeout.ms` 对齐，或接受“重复由消费端幂等兜底”。

---

## 六、本地运行与验证

### 6.1 前置环境

- JDK 21（本机 Maven 默认 `JAVA_HOME` 可能是 JDK 17，需要显式切到 21）
- MySQL 8（库 `web3_backend`）
- Redis 7
- Kafka `localhost:9092`：当前本机使用已存在的 `kafka` 容器（KRaft 单节点）。仓库内 `docker-compose.yml` 已在提交 `4f5040d` 删除；若重建 compose，`KAFKA_ADVERTISED_LISTENERS` 必须同时包含 `CONTROLLER://localhost:9093`，否则报 `0.0.0.0 nonroutable`。

### 6.2 初始化

```bash
mysql -u root -p < sql/schema.sql
mysql -u root -p < sql/ledger_test_data.sql
mysql -u root -p < sql/kafka_demo_data.sql

# 已有库（schema.sql 的 CREATE TABLE IF NOT EXISTS 不会加新列）
mysql -u root -p < sql/migration/20261118_outbox_partition_key.sql
```

### 6.3 启动应用

```bash
export WEB3_OUTBOX_TRANSPORT=kafka            # 当前配置默认已是 kafka，显式设置更清楚
export KAFKA_BOOTSTRAP_SERVERS=localhost:9092
export WEB3_PLATFORM_LIABILITY_ACCOUNT_ID=900101
export WEB3_WS_ENABLED=true                   # 本地 Anvil 实时入口
mvn spring-boot:run
```

### 6.4 触发一笔 USER 充值

向 **USER 地址** `0x3c44cdddb6a900fa2b585dd299e03d12fa4293bc` 转 USDT（合约 `0x5FbDB...`）。
不要用 `0x7099...`：它是配置中心热钱包，没有 userId，消费者会按设计拒绝入用户账本。

### 6.5 验证 SQL

```sql
-- 1. 充值记录
SELECT deposit_no, user_id, wallet_type, currency, status, notify_status
FROM deposit_record ORDER BY id DESC LIMIT 5;

-- 2. Outbox 状态（应为 SENT；停投递器时应为 NEW）
SELECT id, biz_no, partition_key, status, retry_count, next_retry_at
FROM outbox_message ORDER BY id DESC LIMIT 5;

-- 3. 消费幂等
SELECT * FROM consumer_biz ORDER BY consumed_at DESC LIMIT 5;

-- 4. 账本分录
SELECT entry_no, biz_type, biz_no, status
FROM ledger_entry ORDER BY id DESC LIMIT 5;

-- 5. 余额（用户 + 平台负债应一正一负）
SELECT id, user_id, account_type, currency, available, frozen, version
FROM account WHERE id IN (900001, 900101);
```

### 6.6 关键日志

```text
[deposit] 充值路由 source=STREAM events=1 matched=1 inserted=1 outbox=1
[outbox] 投递成功 id=... bizType=DEPOSIT bizNo=DP... retry=0
[consumer] 账本入账成功 depositNo=DP... entryNo=LE... userAccountId=900001 amount=... USDT
[consumer] 已 ack depositNo=DP... entryNo=LE... partition=... offset=...
```

### 6.7 Topic 分区

```bash
kafka-topics.sh --bootstrap-server localhost:9092 --describe --topic deposit-outbox
# 确认 PartitionCount=6、Isr=1

# 已存在的 topic 改配置不会自动扩分区：
kafka-topics.sh --bootstrap-server localhost:9092 --alter --topic deposit-outbox --partitions 6
```

---

## 七、验收结果

### 7.1 单测

与 Outbox/消费者直接相关的 9 个测试类，共 **41 个用例**，全部通过（2026-11-20 复跑）：

| 测试类 | 用例数 | 覆盖点 |
|---|---:|---|
| `OutboxDispatcherTest` | 6 | 扫描、投递、退避、SENT/FAILED |
| `RetryPolicyTest` | 7 | 指数/jitter/封顶/耗尽 |
| `RedisOutboxPublisherTest` | 2 | Redis 通道 |
| `KafkaOutboxPublisherTest` | 3 | partitionKey / bizNo 回退 |
| `DepositOutboxConsumerTest` | 4 | ack/不 ack/CommitFailedException |
| `DepositCreditServiceTest` | 5 | 消费入账逻辑 |
| `DepositCreditServiceTransactionTest` | 2 | consumer_biz 幂等 + H2 回滚 |
| `DepositRoutingOutboxTransactionTest` | 2 | 业务 + Outbox 同事务/回滚 |
| `DepositRoutingServiceImplTest` | 10 | 路由、Outbox、地址反查、分区键 |
| **合计** | **41** | 相关测试全绿 |

### 7.2 本地端到端数据（2026-11-18）

| 指标 | 数值 |
|---|---|
| `deposit_record` | 5 条 |
| `outbox_message` | 5 条，全部 SENT |
| `consumer_biz` | 5 条 |
| `ledger_entry(DEPOSIT)` | 5 条，无重复 biz_no |
| `account 900001`（用户 10001 SPOT USDT） | `available=1005`，`version=5` |
| `account 900101`（平台代收负债 USDT） | `available=-5`，`version=5` |

> 历史 3 条 HOT 记录当时 outbox payload 带了 `userId=10001`，所以被入账。按当前代码，HOT 地址反查不到用户会保持 `userId=null`，消费者拒绝；端到端演示请使用 USER 地址。

---

## 八、注意事项与避坑

1. **顺序**：必须先 `publish` 成功，再 `markSent`；绝不能先标记 SENT 再发送。
2. **重复是设计的一部分**：at-least-once 必然重复；`consumer_biz` + `uk_biz` 是前提，不是优化。
3. **消费异常不得 ack**：catch 住异常还 commit 会导致消息永久丢失；当前实现只有 `credit()` 成功后才 ack。
4. **`CommitFailedException` 不是入账失败**：rebalance 导致 offset 提交失败时，消息会重投；不要把它当业务失败重试，也不要吞掉后继续提交 offset。
5. **投递器扫描**：必须命中 `idx_status_next(status, next_retry_at)`，只查显式列，避免 `SELECT *` 全表扫。
6. **`SKIP LOCKED` 必须在事务内**：扫描、发送、改状态在同一事务；否则锁提前释放，多实例会重复抢同一批。
7. **事务持有网络 IO**：当前投递在事务内发 Kafka，broker 慢会拉长行锁时间；生产可改“事务内标记 SENDING、事务外发送、再回写 SENT”，需要租约/超时字段。
8. **发送超时 ≠ 取消发送**：`get(5s)` 超时后 producer 可能仍写成功，Outbox 重试会产生重复；重复由消费端幂等兜底。
9. **Kafka 持久性**：本地 RF=1，`acks=all` 只等 leader；生产 RF≥3 + `min.insync.replicas=2` + `unclean.leader.election.enable=false`。
10. **retention vs lag**：消费者停机超过 retention，未消费消息会被删除，outbox 已是 SENT 不会补发；必须监控 lag 时间。
11. **分区键**：`partition_key=userId`；HOT 回退 `depositNo`；分区数变更会改变 key→partition 映射，扩分区后同一用户新旧消息可能不在同一分区。
12. **`transport` 大小写敏感**：必须是 `kafka` 或 `redis`；写成 `Kafka` 会导致两个 Publisher 都不加载，启动因缺少 `OutboxPublisher` 失败。
13. **Spring Boot 4 依赖**：必须用 `spring-boot-starter-kafka`，只引 `spring-kafka` 不会有 `KafkaAutoConfiguration`，运行时找不到 `KafkaTemplate`。
14. **断点调试**：暂停 JVM 超过 `session.timeout.ms`（默认 45s）会触发 rebalance；长时间断点用 `KAFKA_SESSION_TIMEOUT_MS=300000`。
15. **HOT 钱包**：平台归集地址没有 userId，消费者会拒绝入用户账本；不要为了“让它入账”手工往 payload 塞 userId。
16. **地址反查**：只查 `wallet_type='USER'` 且 `status=1`；缓存有 30s TTL，写 Outbox 前以 DB 反查结果为准，并在不一致时回写 `deposit_record`。
17. **`next_retry_at` 用数据库时钟**：不要用 JVM 时间赋值，避免时区/时钟偏差。
18. **payload 是入账时快照**：在业务事务内生成，投递时不再查业务表；否则下游拿到的是修改后的状态。
19. **`notify_status` 与 outbox status 是两层**：`notify_status` 面向业务可观测；`outbox_message.status` 面向投递生命周期；FAILED 目前需人工补偿（DLQ 在 W4）。
20. **迁移**：已有库必须执行 `sql/migration/20261118_outbox_partition_key.sql`；只跑 `schema.sql` 不会给旧表加列。
21. **Topic 分区**：`application.yml` 改 `partitions` 不会自动修改已存在 topic；需 `kafka-topics.sh --alter` 或重建 topic。
22. **本地 Kafka**：当前仓库没有 `docker-compose.yml`；如重建 KRaft 单节点，`KAFKA_ADVERTISED_LISTENERS` 必须同时包含 `CONTROLLER://...`，否则启动校验失败。

---

## 九、未完成 / 下一周

| 项 | 现状 | 计划 |
|---|---|---|
| 消费重试策略 / DLQ | 失败不 ack、无限重投 | W4：重试次数、退避、`dlq_message`、告警 |
| `FAILED` 自动补偿 | 仅状态位 | W4：退款/解冻或人工补偿流程 |
| lag 指标 | 未暴露 | W4：`kafka_consumer_lag` / endOffset-currentOffset |
| SENT → CONSUMED 闭环 | 仅 broker ack | 可选增强：消费者回写、长期未消费对账 |
| WS 断线重连实跑记录 | 代码已有 | 手动断开 WS，记录重连、不重复、不遗漏 |
| 多副本 Kafka | 本地单副本 | 生产 RF=3、minISR=2、监控 ISR |

---

## 十、关键文件与提交

**核心文件**

```text
src/main/java/com/ray/web3/service/impl/DepositRoutingServiceImpl.java
src/main/java/com/ray/web3/service/impl/OutboxDispatcher.java
src/main/java/com/ray/web3/service/impl/RedisOutboxPublisher.java
src/main/java/com/ray/web3/service/impl/KafkaOutboxPublisher.java
src/main/java/com/ray/web3/service/impl/DepositCreditService.java
src/main/java/com/ray/web3/listener/DepositOutboxConsumer.java
src/main/java/com/ray/web3/scanner/OutboxDispatchScheduler.java
src/main/java/com/ray/web3/config/OutboxProperties.java
src/main/java/com/ray/web3/config/KafkaOutboxConfig.java
src/main/java/com/ray/web3/mapper/OutboxMessageMapper.java
src/main/java/com/ray/web3/mapper/ConsumerBizMapper.java
sql/schema.sql
sql/migration/20261118_outbox_partition_key.sql
```

**关键提交**

- `6fb7e50` / `60799bc`：Outbox 同事务 + 投递器 + Kafka 消费者
- `b08e5f6`：Boot 4 `spring-boot-starter-kafka` 修复
- `e9181b6`：消费者 rebalance / `CommitFailedException` 处理
- `91d9d5e`：写 Outbox 前按收款地址反查 userId
- `832f5f6`：`partition_key=userId` 与 6 分区
- `c7dcf82`：Day 7 风险表与验收记录

**相关文档**

- `file/第三阶段/Day5-6消费者与本地跑通.md`
- `file/第三阶段/Day7风险表与自测.md`
- `README.md` 5.16