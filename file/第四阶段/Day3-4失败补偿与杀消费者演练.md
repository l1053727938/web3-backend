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

## 五、演练记录（模板）

> 说明：本次实现环境没有可用 Kafka broker / Docker Kafka 容器，演练未实际执行。
> 下表为执行后应填写的记录格式。

| 项目 | 数值 |
|---|---|
| 演练时间 | 待填 |
| 停消费者时长 | 5 min |
| 停前 `deposit_record` 总数 | N |
| 期间新增充值 | X |
| 重启后 `deposit_record` 总数 | N + X |
| 重启后 `consumer_biz` 总数 | 待填 |
| 重复 `(DEPOSIT, biz_no)` 组数 | 0（期望） |
| `kafka_consumer_lag` 峰值 | 待填 |
| lag 归零耗时 | 待填 |
| 账本 posting 条数 | 待填 |
| 提币解冻笔数 | 待填 |

---

## 六、验收对照

| 验收项 | 实现/状态 |
|---|---|
| FAILED 提币自动解冻 | ✅ `WithdrawRefundScanner` + `LedgerService.refundWithdrawFreeze` |
| 解冻幂等（`withdraw_no_REFUND`） | ✅ `uk_biz` + `refund_status` |
| 杀消费者 5 分钟不丢不重 | ⚠️ 代码链路已具备，演练未执行（无 Kafka 环境） |
| lag 指标暴露到 Actuator | ✅ `kafka_consumer_lag` |
| 演练记录 | 模板已给出，数值待实际执行后填写 |

---

## 七、注意事项

1. **补偿依赖原冻结分录**：提币链路尚未接入用户资产冻结前，`refundWithdrawFreeze` 会返回 null，
   扫描器直接把 `refund_status` 置 REFUNDED，不会误加钱；
2. **负余额**：解冻走 `postInternal(..., false)`，冻结余额不足会失败并保留重试，
   不会把用户余额扣成负数；
3. **消息重复**：Outbox 至少一次投递，消费端靠 `consumer_biz` 主键幂等；
   账本侧还有 `(DEPOSIT, depositNo)` 兜底，两层都重复才会漏；
4. **lag 归零 ≠ 全部入账**：lag 只代表 offset 追平，业务是否入账还要看 `consumer_biz` 与账本条数。
