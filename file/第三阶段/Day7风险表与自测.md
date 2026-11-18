# 第 3 周 Day 7：风险表 + 自测（2026-11-18）

> **结论**
> - Day 5-6 链路已完成：链上事件 → `deposit_record` → `outbox_message` → Kafka → 消费者 → 账本分录。
> - 两项验收均有自动化测试 + 本地 MySQL 数据双重验证。
> - Kafka 分区键已按 `user_id` 对齐：`outbox_message.partition_key`（HOT 回退 bizNo），topic 已扩到 6 分区；实现与验证见 2.3。

---

## 一、风险表

| 风险 | 典型触发 | 不兜底的后果 | 本项目兜底 | 证据 |
|---|---|---|---|---|
| **消息丢失** | 业务提交后、投递前进程崩溃；旧写法“先落库再发消息” | 用户充值永远不到账 | ① `deposit_record` 与 `outbox_message` 同一个本地事务写入（`DepositRoutingServiceImpl.route` 的 `@Transactional`）；<br>② 独立投递器扫描 `status='NEW' AND next_retry_at <= NOW(3)`，按指数退避重试；<br>③ 超过 `max-retries` 置 `FAILED` 转人工补偿；<br>④ 消费者处理成功才 ack，失败不提交 offset，重启/rebalance 后重投 | `DepositRoutingOutboxTransactionTest` 2/2；`OutboxDispatcherTest` 6/6 |
| **消息重复** | 投递成功但改状态前宕机；Kafka 至少一次投递；rebalance 重投 | 重复入账，用户余额虚增 | ① `consumer_biz` 主键 `INSERT IGNORE`，命中即 `duplicate=true` 直接 ack（`DepositCreditService.credit`）；<br>② 账本 `ledger_entry.uk_biz(biz_type, biz_no)` 幂等，重复调用返回原分录、不再动余额（`LedgerServiceImpl.postInternal`）；<br>③ 两层任意一层命中都不会二次加钱 | `DepositCreditServiceTransactionTest` 2/2；`LedgerServiceImplTest` 8/8（`uk_biz` 用例） |
| **消息乱序** | 多分区并发消费；同一用户连续多笔事件 | 纯加法充值最终余额仍正确；但冻结/扣减/结算等**非交换操作**可能余额不足误拒或状态错乱 | ① 账本按 `account_id` 升序 `FOR UPDATE` 加锁，避免死锁与并发覆盖；<br>② 回写走 `version` 乐观锁，冲突整组回滚；<br>③ 分区键 `partition_key=userId`（HOT 回退 bizNo），保证同一用户进同一分区（见 2.3） | `LedgerServiceImplTest`；`KafkaOutboxPublisherTest` |
| **投递持续失败/积压** | broker 不可用、网络分区 | 消息堆积，充值确认延迟 | `retry_count` + `OutboxBackoff` 指数退避；达上限置 `FAILED`；扫描走 `idx_status_next(status, next_retry_at)` | `OutboxDispatcherTest` |
| **消费毒消息** | payload 损坏、账户不存在、账本异常 | 无限重投、阻塞分区 | 当前不 ack + 重投；DLQ 留到第 4 周（计划已列） | `DepositCreditService` 失败路径单测 |

---

## 二、自测题

### 2.1 为什么不能先发消息？

“先发消息再落库”是典型的双写不一致：

1. 消息已经发到 Kafka / Redis，下游消费者立即入账；
2. 本地数据库事务随后回滚（余额不足、唯一键冲突、进程崩溃等）；
3. 下游账已经记了，但业务事实不存在 → **资损**；
4. Kafka 不支持按单条消息撤回，只能靠下游反向冲正，成本和风险都很高。

反过来“先落库再发消息”也有窗口：落库成功、进程在 publish 前挂掉 → 消息永久丢失，用户充值不到账。

Outbox 的本质是把这两个动作拆成：

- **同一个本地事务**写“业务事实 + 待发消息”（原子性）；
- 独立投递器基于**已提交**的数据发消息（可重试、可观测）。

即：不存在“消息发了业务没成”，也不存在“业务成了消息丢了”的中间态。

### 2.2 消费成功但 commit 失败怎么办？

分两种“commit”：

**① Kafka offset commit 失败（rebalance / 网络）**

- 入账事务已经提交（`consumer_biz` + `ledger_entry` 都已落库），只是 offset 没推进；
- 消息会被重投；
- 重投时 `consumer_biz` 主键冲突 → `duplicate=true` → 直接 ack；
- 即使 `consumer_biz` 被清空，`ledger_entry.uk_biz` 也会返回原分录，不会二次加钱；
- 当前消费者对 `CommitFailedException` 单独捕获并打 WARN（不再误报“入账失败”），见 `DepositOutboxConsumer`。

**② 本地事务 commit 失败（业务库）**

- 消费者不 ack → 消息重投；
- 若账本事务已成功、业务事务回滚：重投时 `consumer_biz` 重新插入，账本按 `uk_biz` 幂等返回原分录；
- 最终结果仍是“恰好一次入账”。

关键前提：**消费者只在 `credit()` 成功返回后才 `acknowledge()`**，异常路径绝不提交 offset。

### 2.3 分区键为什么选 `user_id`？

Kafka 只保证**同一 partition 内有序**，跨 partition 不保证顺序。选 `user_id` 作为 key 的意义：

- 同一用户的充值/冻结/结算等事件落到同一 partition，按生产顺序消费；
- 不同用户之间无需顺序，可以并行；
- 对非交换操作（先冻结再扣减、先下单再成交）尤其重要，乱序会导致余额不足误拒或状态机错乱。

**当前实现（2026-11-18 已对齐）：**

`outbox_message` 新增 `partition_key` 列，写 Outbox 时按业务语义赋值，投递器优先用它：

```java
// DepositRoutingServiceImpl.enqueueOutbox
message.setPartitionKey(record.getUserId() != null && !record.getUserId().isBlank()
        ? record.getUserId() : record.getDepositNo());

// KafkaOutboxPublisher.publish
String key = StringUtils.hasText(message.getPartitionKey())
        ? message.getPartitionKey() : message.getBizNo();
kafkaTemplate.send(topic, key, message.getPayload());
```

- USER 充值 → key = `userId`，同一用户进同一 partition；
- HOT 充值 → 无归属用户，key 回退 `depositNo`（保证 key 非空、可排查）；
- 历史/其他业务未设置 key → 回退 `bizNo`，向后兼容；
- topic 已从 1 分区扩到 6 分区（`kafka-topics.sh --alter`），`application.yml` 的 `web3.outbox.kafka.partitions` 默认 6。

> 注：分区数扩容后，同一 key 的新旧消息可能落到不同分区；生产上应一次性定好分区数或使用固定桶分区器。当前本地已重建/扩容 topic，新消息按 userId 分区。

---

## 三、验收对照

### 3.1 验收一：停掉投递器，业务照常落库

**代码事实：**

- 路由事务只做两件事：写 `deposit_record` + 写 `outbox_message`，见 `DepositRoutingServiceImpl.route()`；
- 投递器是独立 Bean `OutboxDispatchScheduler`，由 `web3.outbox.enabled` 控制；停掉它不影响路由事务提交；
- 消息保持 `NEW`，重启投递器后继续投递，不会丢。

**自动化验证：** `DepositRoutingOutboxTransactionTest`（2/2 通过）

- `正常路径充值记录与outbox消息同事务提交`：两表各 1 行；
- `outbox写入失败时充值记录随事务一起回滚`：outbox 插入失败 → `deposit_record` 也为 0；
- 证明“同生共死”，且投递器不参与路由事务。

**手动复现（可选）：**

```bash
# 1. 关闭投递器启动应用（消费者仍可关掉：transport 保持 kafka 但 outbox.enabled=false）
WEB3_OUTBOX_ENABLED=false

# 2. 向 USER 地址 0x3c44... 转一笔 USDT，触发链上事件

# 3. 业务表已落库、消息仍未投递
SELECT deposit_no, user_id, notify_status FROM deposit_record ORDER BY id DESC LIMIT 1;
SELECT id, biz_no, status, retry_count FROM outbox_message ORDER BY id DESC LIMIT 1;
-- 期望：deposit_record 有行；outbox_message status=NEW

# 4. 打开投递器重启，消息变为 SENT
```

### 3.2 验收二：手插两条同 `biz_no`，只落一次账

**自动化验证：**

- `DepositCreditServiceTransactionTest`（2/2 通过）
  - 同一 `depositNo` 第一次 `duplicate=false`，第二次 `duplicate=true`；
  - `consumer_biz` 只有 1 行；
  - `ledgerService.deposit(...)` 只被调用 1 次（`times(1)`）。
- `LedgerServiceImplTest`（8/8 通过）
  - `uk_biz` 命中时返回原分录，不再更新账户余额。

**手动复现：**

```bash
docker exec -it kafka /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server localhost:9092 --topic deposit-outbox
# 连续粘贴两次同一条 payload（相同 depositNo），观察日志：
# 第一次：[consumer] 账本入账成功 ... 已 ack
# 第二次：[consumer] 幂等命中，跳过 ...
```

**本地库端到端证据（2026-11-18）：**

| 指标 | 数值 |
|---|---|
| `deposit_record` | 5 条（USER=2，HOT=3） |
| `outbox_message` | 5 条，全部 `SENT` |
| `consumer_biz` | 5 条（每个 depositNo 一条） |
| `ledger_entry(DEPOSIT)` | 5 条（无重复 biz_no） |
| `account 900001`（用户 10001 SPOT USDT） | available = 1005，version = 5（5 笔 × 1.0） |
| `account 900101`（平台代收负债 USDT） | available = -5，version = 5 |

> 说明：历史 3 条 HOT 记录当时的 outbox payload 带了 `userId=10001`，因此被入账。按当前代码，HOT 地址反查不到用户会保持 `userId=null`，消费者会拒绝——端到端演示请使用 USER 地址 `0x3c44cdddb6a900fa2b585dd299e03d12fa4293bc`。

---

## 四、避坑清单核对

| 计划中的坑 | 当前实现 | 结论 |
|---|---|---|
| 投递器 `SELECT *` 无索引全表扫 | `OutboxMessageMapper.selectPendingBatch` 只查显式列；`outbox_message` 有 `KEY idx_status_next(status, next_retry_at)` | ✅ 已避开 |
| 消费端 catch 住异常还 commit，消息永久丢且无感知 | `DepositOutboxConsumer` 只在 `credit()` 成功后 `ack.acknowledge()`；异常分支不 ack；`CommitFailedException` 单独 WARN，且 offset 未提交会重投 | ✅ 已避开 |
| 投递成功但状态未回写 | 至少一次重投，`consumer_biz` + `uk_biz` 兜底 | ✅ 已覆盖 |

---

## 五、本次测试结果

执行时间：2026-11-18 14:52（JDK 21）

| 测试类 | 结果 | 证明 |
|---|---|---|
| `DepositRoutingOutboxTransactionTest` | 2/2 通过 | 业务 + Outbox 同事务；Outbox 失败业务回滚 |
| `DepositCreditServiceTransactionTest` | 2/2 通过 | 同一 `biz_no` 只消费一次；账本只调一次 |
| `LedgerServiceImplTest` | 8/8 通过 | `uk_biz` 幂等、余额、借贷平衡 |
| `OutboxDispatcherTest` | 6/6 通过 | 扫描/投递/退避/失败状态推进 |
| `KafkaOutboxPublisherTest` | 3/3 通过 | 有 `partitionKey` 时按 userId 发送；为空回退 bizNo |
| `DepositRoutingServiceImplTest` | 10/10 通过 | USER 充值 `partition_key=userId`；HOT 回退 depositNo |

完整 `mvn test` 已通过（2026-11-18 15:4x，JDK 21）。