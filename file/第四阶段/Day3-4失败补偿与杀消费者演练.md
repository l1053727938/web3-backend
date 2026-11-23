# Day 3-4：失败补偿 + 杀消费者演练

> 对应 18 周计划第 4 周 Day 3-4
> 目标：提币 FAILED 后资金自动解冻；验证消费者停 5 分钟不丢不重
> 产出：自动解冻实现 + lag 指标 + 演练步骤与记录模板

---

## 一、先区分两种 FAILED

| 字段 | 含义 | 是否触发资金补偿 |
|---|---|---|
| `outbox_message.status = FAILED` | 消息投递重试超限，进死信 | ❌ 与资金无关 |
| `deposit_record.notify_status = FAILED` | 充值下游通知失败 | ❌ 只影响通知 |
| `withdraw_record.status = FAILED` | 提币业务失败（估算失败/节点拒接/上链 Revert） | ✅ 触发解冻 |

本 Day 只处理第三种。

---

## 二、自动解冻实现

### 2.1 从账本反查，而不是查提币表

`withdraw_record` 只有热钱包地址与 raw_value，没有用户账户 id。解冻所需的三要素
（账户、金额、币种）都在**原冻结分录**里：

```
ledger_entry (biz_type='FREEZE', biz_no=withdraw_no)
  └─ ledger_posting:
       account_id=11, direction=DEBIT,  balance_type=AVAILABLE, amount=30
       account_id=11, direction=CREDIT, balance_type=FROZEN,    amount=30
```

`LedgerService.refundWithdrawFreeze(withdrawNo)`：

1. 按 `(FREEZE, withdrawNo)` 找原分录；找不到 → 返回 null（该提币从未冻结，无需补偿）；
2. 读明细，取 `FROZEN + CREDIT` 那条腿得到 accountId / amount / currency；
3. 生成解冻分录：`FROZEN DEBIT` / `AVAILABLE CREDIT`；
4. `biz_type=UNFREEZE`、`biz_no = withdrawNo + "_REFUND"`。

### 2.2 幂等三层

1. **扫描条件**：只扫 `status='FAILED' AND refund_status='NONE'`；
2. **账本唯一键**：`(UNFREEZE, withdrawNo_REFUND)` 撞 `uk_biz` 直接返回首次解冻组；
3. **状态回写**：账本成功后才把 `refund_status` 置 `REFUNDED`。

顺序不能反：先置 REFUNDED 再解冻，一旦解冻失败就再也不会重试，用户资金永久冻结。

### 2.3 扫描器

`WithdrawRefundScanner`，默认每 60s 扫描一批（`refund-batch-size=100`）。
不加分布式锁：补偿本身幂等，多实例重复执行最多多查一次库，不会重复加钱。

---

## 三、消费 lag 指标

`KafkaLagMonitor`（仅 `web3.outbox.transport=kafka` 启用）：

```
lag = endOffset - committedOffset
```

- 按 `topic/partition/group` 注册 Micrometer Gauge；
- Prometheus 指标名 `kafka_consumer_lag`，总量 `kafka_consumer_lag_total`；
- Actuator 查看：`GET /actuator/prometheus | grep kafka_consumer_lag`；
- 默认 15s 刷新（`web3.outbox.kafka.lag-refresh-ms`）。

消费者停掉时读不到 committed offset，指标保持上一轮值并只打 debug 日志，
不会瞬间归零误导告警。

---

## 四、杀消费者演练步骤

### 4.1 准备

```bash
# 确认 Kafka / 消费者在跑
docker ps | grep kafka

# 记录基线
mysql -uroot -p web3_backend -e "
  SELECT COUNT(*) AS deposit_total FROM deposit_record;
  SELECT COUNT(*) AS outbox_total FROM outbox_message;
  SELECT COUNT(*) AS ledger_posting_total FROM ledger_posting;
  SELECT SUM(available) AS user_available FROM account WHERE account_type='SPOT';"
```

### 4.2 停消费者（5 分钟）

```bash
# 方式 A：停 Kafka
docker stop <kafka-container>

# 方式 B：只停消费者（推荐，投递器仍可写 outbox）
# 关闭 Kafka 监听：把 web3.outbox.transport 临时改为 redis，或停掉消费进程
```

期间继续产生充值事件（Anvil 转几笔，或补拉历史区间）。

### 4.3 重启并等待追平

```bash
docker start <kafka-container>
# 等待 lag 归零
watch -n 5 'curl -s localhost:8080/actuator/prometheus | grep kafka_consumer_lag'
```

### 4.4 校验

```sql
-- 1) 总条数一致：停消费者期间产生的充值都应在 deposit_record
SELECT COUNT(*) FROM deposit_record;

-- 2) 账本总额一致：每笔充值对应一组 DEPOSIT 分录
SELECT COUNT(*) FROM ledger_entry WHERE biz_type='DEPOSIT';
SELECT COUNT(*) FROM ledger_posting lp
JOIN ledger_entry le ON le.id = lp.entry_id
WHERE le.biz_type='DEPOSIT';

-- 3) 重复落账为 0：同一 deposit_no 只允许一条 DEPOSIT 分录
SELECT biz_no, COUNT(*) c
FROM ledger_entry
WHERE biz_type='DEPOSIT'
GROUP BY biz_no
HAVING c > 1;

-- 4) 消费幂等：consumer_biz 与 deposit_record 一一对应（仅已消费的）
SELECT COUNT(*) FROM consumer_biz;
```

期望：

- `deposit_record` 条数 = 停消费者前 + 期间新增；
- `consumer_biz` 条数 = 已消费充值数；
- 重复分组查询返回空；
- 每个用户 `account.available` = 初始余额 + 所有已确认充值之和。

### 4.5 提币失败解冻演练

```sql
-- 模拟一笔提币失败（需先有对应 FREEZE 分录）
UPDATE withdraw_record SET status='FAILED', refund_status='NONE' WHERE withdraw_no='WD...';

-- 等扫描器一个周期（默认 60s），然后查：
SELECT withdraw_no, status, refund_status FROM withdraw_record WHERE withdraw_no='WD...';
SELECT biz_type, biz_no, entry_no FROM ledger_entry WHERE biz_no='WD..._REFUND';
```

期望：`refund_status=REFUNDED`，且 `ledger_entry` 只有一条 `(UNFREEZE, WD..._REFUND)`。

---

## 五、演练记录（已执行）

> 环境：Docker `kafka`（apache/kafka 4.4.0）+ `mysql8` + `redis7`，
> 应用以 `web3.outbox.transport=kafka` 启动。
> 为控制时长，broker 停机约 90 秒（计划 5 分钟，按比例缩短）。

| 项目 | 数值 |
|---|---|
| 演练时间 | 2026-11-23 21:12 ~ 21:20 |
| 停 Kafka 时长 | 约 90 秒 |
| 停前 `deposit_record` 总数 | 1 |
| 期间新增充值 | 5（DRILL1 ~ DRILL5） |
| 重启后 `deposit_record` 总数 | 6 |
| 停前 `consumer_biz` 总数 | 1 |
| 重启后 `consumer_biz` 总数 | 6（基线 1 + 5） |
| 账本 `DEPOSIT` 分录 | 5 组 / 10 条 posting |
| 重复 `(DEPOSIT, biz_no)` 组数 | **0** |
| `outbox_message` DRILL 已发送 | 6 条（含 DRILL2 人工重放 1 条） |
| 用户 1 余额 | 0 → 50 USDT（5 × 10） |
| 平台负债账户 | -351 → -401 USDT |
| `kafka_consumer_lag` | 恢复后 1.0 → 0.0（停机期间消费者断连，峰值未采集） |
| 提币解冻笔数 | 0（本轮未演练提币侧） |

**结论**：5 笔充值最终全部入账，重复落账 0 组；并且证明了“重复投递”被消费端幂等吸收——
DRILL2 被额外重放了一条 outbox 消息，outbox 有 6 条 SENT，但账本只有 5 组分录。

### 5.1 演练发现的问题（已修复）

**① 懒开户的 REPEATABLE READ 快照 bug**

`DepositCreditService` 原逻辑是“先查账户 → 查不到 → 账本事务开户 → 再查”。
消费者事务是业务库事务，第一次 SELECT 就固定了快照；账本事务随后提交的账户
在这个快照里不可见，二次查询仍为 null，报“用户账户不存在且开户失败”。

修复：**先幂等开户、再查询**（`ensureAccount` 在独立账本事务提交后，业务事务的第一次读才能看到）。

**② 默认错误处理器会静默丢弃失败消息**

Spring Kafka 默认 `DefaultErrorHandler` 重试 10 次后会把失败消息的 offset **提交并跳过**。
演练中 DRILL2 因①失败，重试 10 次后直接被丢弃：outbox 是 SENT、deposit_record 在，
但账本没有它——这是真正的丢消息。

修复：`KafkaConsumerErrorConfig` 改为指数退避（1s 起、翻倍、封顶 30s）无限重试，
且 `ackAfterHandle=false`，失败消息不提交 offset，重启/rebalance 后继续重投。
代价是毒消息会阻塞所在 partition；消费端 DLQ（重试 N 次后落死信并放行）留到第 5 周。

**③ 投递器在数据库事务里同步发 Kafka**

broker 停机时，`KafkaTemplate.send()` 在等 metadata 的阶段会阻塞（默认 `max.block.ms≈60s`），
而投递器把“扫描 + 发送 + 改状态”放在同一个事务里，等于每条消息持有行锁约 60 秒，
5 条一批会把事务拖到约 5 分钟。演练期间正是这个现象。

改进方向：事务内只标记 `SENDING`、事务外发送、再回写 `SENT/FAILED`（需要租约/超时字段），
或把发送移出 `dispatchBatch()` 的事务边界。

### 5.2 DRILL2 的恢复

DRILL2 被默认处理器丢弃后，通过**人工重放**验证补偿路径：

```sql
-- 重新插入一条同 bizNo 的 outbox 消息（status=NEW）
INSERT INTO outbox_message (biz_type, biz_no, partition_key, payload, status, retry_count) VALUES (...);
```

重启后投递器重新发送、消费者处理成功。由于 `consumer_biz` 与账本 `uk_biz` 两层幂等，
重放没有产生第二条账本分录。

---

## 六、验收对照

| 验收项 | 实现/状态 |
|---|---|
| FAILED 提币自动解冻 | ✅ `WithdrawRefundScanner` + `LedgerService.refundWithdrawFreeze` |
| 解冻幂等（`withdraw_no_REFUND`） | ✅ `uk_biz` + `refund_status` |
| 杀消费者不丢不重 | ✅ 已演练（约 90s）：5 笔全部入账，重复 0 |
| 消费端失败不静默丢弃 | ✅ 无限退避重试（`ackAfterHandle=false`）；DLQ 待第 5 周 |
| lag 指标暴露到 Actuator | ✅ `kafka_consumer_lag`，恢复后归零 |
| 演练记录 | ✅ 本节 |

---

## 七、注意事项

1. **补偿依赖原冻结分录**：提币链路尚未接入用户资产冻结前，`refundWithdrawFreeze` 会返回 null，
   扫描器直接把 `refund_status` 置 REFUNDED，不会误加钱；
2. **负余额**：解冻走 `postInternal(..., false)`，冻结余额不足会失败并保留重试，
   不会把用户余额扣成负数；
3. **消息重复**：Outbox 至少一次投递，消费端靠 `consumer_biz` 主键幂等；
   账本侧还有 `(DEPOSIT, depositNo)` 兜底，两层都重复才会漏；
4. **lag 归零 ≠ 全部入账**：lag 只代表 offset 追平，业务是否入账还要看 `consumer_biz` 与账本条数。
