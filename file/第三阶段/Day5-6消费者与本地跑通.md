# Day 5-6：消费者与本地跑通（Outbox → Kafka → 账本）

> 对应 18 周计划第 3 周 Day 5-6
> 目标：链上事件 → 充值落库 → outbox → Kafka → 消费者 → 账本分录，端到端跑通
> 约束：不改链上拉取逻辑（WS 实时订阅 / 分页补拉沿用现有代码）

---

## 一、链路

```
链上 Transfer
  ├─ WS 实时订阅（TransferStreamEngine）
  └─ 分页补拉（HistoryBackfillService）
        │
        ▼
DepositRoutingServiceImpl.route()  ── 同一本地事务 ──┐
        │                                            │
        ├─ INSERT deposit_record                     │
        └─ INSERT outbox_message (status=NEW)        │
                                                     ▼
                              OutboxDispatcher（@Scheduled，FOR UPDATE SKIP LOCKED）
                                                     │
                                                     ▼
                              KafkaOutboxPublisher → topic: deposit-outbox
                                                     │ key=depositNo, value=JSON
                                                     ▼
                              DepositOutboxConsumer（enable.auto.commit=false）
                                                     │ 处理成功才 ack
                                                     ▼
                              DepositCreditService
                                ├─ INSERT IGNORE consumer_biz(biz_no)  ← 重复直接 ack 跳过
                                └─ LedgerService.deposit()             ← uk_biz 幂等
                                                     │
                                                     ▼
                              ledger_entry + ledger_posting + account 余额
```

相关代码：

| 文件 | 职责 |
|---|---|
| `service/impl/OutboxDispatcher.java` | 扫 outbox、调用投递通道、成功 SENT / 失败退避 |
| `service/impl/KafkaOutboxPublisher.java` | Outbox → Kafka（同步等待 broker ack） |
| `listener/DepositOutboxConsumer.java` | `@KafkaListener` + 手动 ack |
| `service/impl/DepositCreditService.java` | consumer_biz 幂等 + 账户解析 + 调账本 |
| `mapper/ConsumerBizMapper.java` | `INSERT IGNORE consumer_biz` |
| `mapper/OutboxMessageMapper.java` | 插入 / `SKIP LOCKED` 扫描 / SENT/FAILED/退避更新 |
| `config/OutboxProperties.java` | 通道、批量、重试、Kafka topic 配置 |
| `config/DepositConsumerProperties.java` | 平台负债账户 / 用户账户类型 |
| `util/OutboxBackoff.java` | 指数退避纯函数 |
| `config/KafkaOutboxConfig.java` | 自动创建 topic |
| `sql/kafka_demo_data.sql` | 演示 USER 充值地址（映射 user_id=10001） |
| `docker-compose.yml` | KRaft 单节点 Kafka |

---

## 二、本地启动

### 1. 启动 Kafka（KRaft 单节点，无 ZooKeeper）

```bash
docker compose up -d kafka
docker compose ps                 # 等 STATUS 变为 healthy（首次约 20~30s）
docker compose logs -f kafka      # 看到 Kafka Server started 即可
```

> 如果启动日志报 `advertised.listeners cannot use the nonroutable meta-address 0.0.0.0`，
> 说明 KRaft 合并模式下 `KAFKA_ADVERTISED_LISTENERS` 没有声明 CONTROLLER 监听器；
> 本项目 `docker-compose.yml` 已配置 `PLAINTEXT://localhost:9092,CONTROLLER://localhost:9093`，
> 且单节点内部 topic 副本数固定为 1。

### 2. 初始化数据库

```bash
mysql -u root -p < sql/schema.sql
mysql -u root -p < sql/ledger_test_data.sql
mysql -u root -p < sql/kafka_demo_data.sql
```

`kafka_demo_data.sql` 登记了一个 USER 充值地址（Anvil 账户 #2），
收到 USDT 后消费者会按 `user_id=10001` 入 `account.id=900001`，平台代收负债账户为 `900101`。

### 3. 启动应用（关键环境变量）

```bash
# 打开 Kafka 通道（同时启用消费者；默认 redis 不启动消费者）
WEB3_OUTBOX_TRANSPORT=kafka
# Kafka 地址（本机 Docker）
KAFKA_BOOTSTRAP_SERVERS=localhost:9092
# 平台代收负债账户（测试数据 900101；生产必须覆盖）
WEB3_PLATFORM_LIABILITY_ACCOUNT_ID=900101

# 链上入口按你本地环境配置：Anvil
WEB3_WS_ENABLED=true
# url 在 application.yml 中配置为 ws://127.0.0.1:8545
# watch-contracts 使用默认的 0x5FbDB... 本地合约
```

> 只在验证 Kafka 链路时设置 `WEB3_OUTBOX_TRANSPORT=kafka`；
> 不设时走 Redis 频道，原有行为不变。

### 4. 触发一笔充值

向 `0x3c44cdddb6a900fa2b585dd299e03d12fa4293bc` 转一笔 USDT
（合约 `0x5FbDB2315678afecb367f032d93F642f64180aa3`），等待 WS/补拉链路处理。

---

## 三、关键三行日志

```text
[deposit] 充值路由 source=STREAM events=1 matched=1 inserted=1 outbox=1
[outbox] 投递成功 id=1 bizType=DEPOSIT bizNo=DP... retry=0
[consumer] 账本入账成功 depositNo=DP... entryNo=LE... userAccountId=900001 amount=100.000000 USDT
[consumer] 已 ack depositNo=DP... entryNo=LE... partition=0 offset=0
```

对应含义：

1. 充值记录与 Outbox 消息同事务落库；
2. Outbox 投递器把消息发到 Kafka 并置 `SENT`；
3. 消费者按 `consumer_biz` 幂等后调账本，写出 `DEPOSIT` 分录并 ack。

---

## 四、幂等验证

### 1. 重复投递

消费者处理逻辑是“先 `INSERT IGNORE consumer_biz`”：

- 同一 `depositNo` 重复消费 → `insertIgnore` 返回 0 → 不调账本，直接 ack；
- 日志出现：`[consumer] 幂等命中，跳过 depositNo=...`

手工验证：用 `kafka-console-producer` 把同一 payload 再发一次，观察第二条日志，
并确认 `ledger_entry` / `account.available` 不再变化。

```bash
docker exec -it web3-kafka /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server localhost:9092 --topic deposit-outbox
# 粘贴 outbox_message.payload 的 JSON，回车
```

### 2. 账本层幂等

即使 `consumer_biz` 被清空/丢失，重复调用 `LedgerService.deposit(depositNo, ...)`
也会命中 `ledger_entry.uk_biz(DEPOSIT, depositNo)`，返回原分录，不会重复加钱。

---

## 五、WS 断线重连验证

`TransferStreamEngine` 已有断线指数退避重建与 `newHeads` 心跳僵尸判定，本单元不改拉取逻辑。

验证步骤：

1. 应用启动并建立 WS 连接；
2. 停掉 Anvil / 断开网络，观察 `[stream]` / `[heartbeat]` 日志进入重连；
3. 断线期间发送一笔转账（链上仍会出块），恢复连接；
4. 恢复后由实时订阅或自动补拉补齐该笔事件；
5. 核对：
   - `deposit_record` 只有一条（唯一键 `chain_id + tx_hash + log_index`）；
   - `ledger_entry` 只有一条 `DEPOSIT`（`uk_biz`）；
   - `account.available` 只增加一次；
   - `consumer_biz` 只有一条。

心跳判定：日志出现 `[heartbeat]` 前缀；连接活着但节点不推数据时，
会用 HTTP 复核链高，链在前进才判定僵尸并重连。详见 `TransferStreamEngine`。

---

## 六、完成核对 SQL

```sql
-- 1. 充值记录：notify_status 应为 SENT
SELECT deposit_no, user_id, currency, display_value, status, notify_status
FROM deposit_record ORDER BY id DESC LIMIT 5;

-- 2. Outbox：应为 SENT，retry_count=0
SELECT id, biz_type, biz_no, status, retry_count, next_retry_at
FROM outbox_message ORDER BY id DESC LIMIT 5;

-- 3. 消费幂等表：应有该 deposit_no
SELECT * FROM consumer_biz ORDER BY consumed_at DESC LIMIT 5;

-- 4. 账本分录
SELECT id, entry_no, biz_type, biz_no, status, reverse_of
FROM ledger_entry ORDER BY id DESC LIMIT 5;

SELECT p.entry_id, p.account_id, p.direction, p.balance_type, p.amount, p.balance_after
FROM ledger_posting p ORDER BY p.id DESC LIMIT 10;

-- 5. 余额：900001 用户可用 +金额，900101 平台负债 -金额
SELECT id, user_id, account_type, currency, available, frozen, version
FROM `account` WHERE id IN (900001, 900101);
```

---

## 七、注意事项

1. **消费者只处理 USER 钱包充值**：HOT 钱包是平台归集地址，payload 没有 `userId`，
   消费者会报错且不 ack；演示请使用 `kafka_demo_data.sql` 里的 USER 地址。
2. **平台负债账户 ID 必须配置**：`web3.deposit.consumer.platform-liability-account-id`，
   本地测试默认 `900101`，生产通过环境变量覆盖。
3. **Kafka 通道只在 `WEB3_OUTBOX_TRANSPORT=kafka` 时启用**：
   默认 `redis` 保持旧行为（Outbox → Redis 频道），不会启动 Kafka 消费者。
4. **手动 ack**：`spring.kafka.consumer.enable-auto-commit=false` +
   `spring.kafka.listener.ack-mode=manual_immediate`，只有处理成功才提交 offset；
   失败不 ack，重启/rebalance 后重投，账本按 `depositNo` 幂等。
5. **Outbox 投递器事务内发 Kafka**：当前批次小、本地低延迟；接生产 Kafka 时可改成
   “事务内标记 SENDING、事务外发送、再回写 SENT”，需要额外的租约/超时字段。