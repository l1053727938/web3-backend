# Day 5-6：INIT 锚点与分片并发（含 Reorg 回滚演练）

> 对应 18 周计划第 5 周 Day 5-6
> 目标：INIT 提币能被链上真相接管；对账按 user_id 分片并发；已入库充值能被重组回滚
> 产出：状态转换表 + 并发骨架 + Reorg 演练记录

---

## 零、这些能力解决什么问题

三个能力看似无关（INIT 接管、分片并发、重组回滚），其实解决的是同一类问题：
**链上事实与本地状态不一致时，系统怎么安全地收敛。**

### 0.1 场景一：提币卡在 INIT，不知道该不该退钱

```
10:00:00  用户申请提币 100 USDT，资金已冻结，withdraw_record 落库 status=INIT
10:00:01  签名完成，调用 eth_sendRawTransaction
10:00:02  网络超时 / 应用重启 → 没拿到 txHash，也没有回执
```

此时系统不知道：钱已经发出去了（用户会到账），还是根本没发出去（用户没到账）。

原来的代码只扫 `BROADCAST`，INIT 没有任何任务接管，资金会一直冻结，只能人工上链查。
两种“顺手处理”都会造成资损：

- **直接判失败并解冻**：万一交易其实上链了，用户链上收到 100、平台又退 100 → 双花；
- **直接补发一笔**：两笔都上链，用户收到 200。

本日实现的三态判定：

| 查到什么 | 结论 | 钱怎么处理 |
|---|---|---|
| 本笔交易在链上且成功 | SUCCESS | 不解冻，落终态 |
| 本笔交易在链上但执行失败 | FAILED | 解冻退款 |
| 同 nonce 被另一笔交易占用 | FAILED（被顶替） | 解冻退款 |
| 查不到，但 nonce 仍被占着 | PENDING | 保持 INIT，下一轮再查 |
| 查不到，nonce 仍空闲 | PENDING | 保持 INIT |

核心：**只有“可证明失败”才动钱**；回拨 nonce 也不自动做——回拨错位置会覆盖在途交易，
比卡单更危险。

### 0.2 场景二：对账把主库打挂

日终对账要关联链上流水、`deposit_record`、`ledger_entry/posting`、`account`，
一天几十万到几百万条。单线程全表扫的结果是：

- 一条大 SQL 占满主库 CPU/IO；
- 线上充值、提现、下单全部变慢；
- 对账跑两小时，主库抖动两小时。

分片并发把压力钳住：

- 按 `user_id` 哈希分 8 片，同一用户固定同一片；
- 每片独立线程池 core=2，一片卡住不拖累其他片；
- 所有分片共享 **50 QPS 令牌桶**，总量封顶；
- 计划中查询走只读从库，主库不参与。

本质：**对账是批处理，不能和在线业务抢同一个数据库。**

> 本次只完成分片 + 限流，只读从库的第三个 DataSource 尚未接入（见 2.3）。

### 0.3 场景三：链重组把已入账的充值抹掉

```
10:00  用户充值 100 USDT，够确认数，deposit_record=CREDITED，账本给用户 +100
10:05  链发生浅块重组，那个块被丢弃，交易消失
```

结果：用户账上多了 100，可以交易/提现；链上平台其实没收到这笔钱，平台凭空多一笔负债。

实时链路虽然有 `isRemoved()`，但它只能丢弃**新到的**撤销日志，
对“昨天已经入库、已经入账”的记录没有任何回退能力——本日补的正是后者。

回滚机制：

```
扫描最近 N 块内 CREDITED 的充值
  → 查交易回执：回执没了 / 对应 logIndex 没了 → 判定被重组
  → 先写冲正分录（原分录不动）
  → 再把 deposit_record 置 INVALID
```

顺序不能反：先置 INVALID 再冲正，一旦冲正失败就再也扫描不到，账永远更正不了。

对账侧配合：**重组差异归“在途”，不归“短款”。** 账上 100、链上 0 看起来是长款，
但它会随冲正自动收敛；若按长短款触发熔断，平台可能直接停提币。

### 0.4 三条线共同的判断原则

1. **不确定不动钱**：INIT 看不出来就保持 INIT，重组没确认就不动账；
2. **可证明才落终态**：receipt、同 nonce 占用这类硬证据才转 SUCCESS/FAILED；
3. **原事实只增不改**：冲正是新分录，差异落 `reconcile_diff`，不改历史账；
4. **自动只做能判定的部分**，剩下的留给人工，而不是猜。

一句话：**链上系统最怕的不是状态不一致，而是在不确定的时候自作聪明地改钱。**

---

## 一、INIT 三态判定

### 1.1 背景

`WithdrawStatusTracker` 只扫 `BROADCAST`，`INIT` 从第 3 周起从未被接管：

```
INIT（已落库，广播结果不确定）
  ├─ 网络超时：可能已进内存池，也可能没发出去
  ├─ 广播成功但回填 txHash 前宕机
  └─ 一直停在 INIT，永远没有下一步
```

现在由 `WithdrawInitRecoveryService` 按“扫链真相”接管。

### 1.2 状态转换表（本日核心产出）

| # | 链上事实 | 判定 | 处置 | 回拨 nonce | 是否解冻 |
|---|---|---|---|---|---|
| 1 | 本地 txHash 有 receipt，`status=0x1` | **SUCCESS** | `markInitSuccess` | 否 | 否 |
| 2 | 本地 txHash 有 receipt，`status=0x0` | **FAILED** | `markInitFailed` | 否 | 是（refund scanner） |
| 3 | 本地有 hash，链上同 `(from,nonce)` 是**另一笔**交易 | **FAILED（被顶替）** | `markInitFailed`，记录占用 hash | **否（红线）** | 是 |
| 4 | 本地无 hash，链上同 nonce 有交易 | **PENDING** | `touchInit` | 否 | 否 |
| 5 | nonce 已消耗但扫不到交易 | **PENDING** | `touchInit` | 否 | 否 |
| 6 | 池内未见、nonce 仍空闲 | **PENDING** | `touchInit` | 否 | 否 |

**为什么第 4 行不判失败**：本地没有 hash，无法证明链上那笔不是“广播成功但回填失败”的本笔；
判失败会触发解冻，形成重复退款。宁可保持 INIT 等人工确认。

### 1.3 取数顺序

```
1. 本地有 txHash → eth_getTransactionReceipt
     有 receipt → 已上链，status 决定成功/失败
2. 没有 receipt / 没有 hash
     → 回扫最近 N 个区块（eth_getBlockByNumber fullTx=true）
       按 (from, nonce) 找占用交易
3. eth_getTransactionCount(from, PENDING)
     区分“nonce 仍空闲”和“已消耗但看不到交易”
```

配置（`web3.withdraw.*`）：

```yaml
init-recovery-enabled: true
init-recovery-interval-ms: 60000
init-recovery-batch-size: 20
init-recovery-lookback-blocks: 100
```

### 1.4 红线

**自动任务永不回拨 nonce。** 回拨只能人工做，且只能回拨到“确认无人使用”的位置；
回拨错位会覆盖在途交易，比卡单更危险。

判定逻辑落在纯函数 `InitRecoveryPolicy`，RPC 取数在 Service，便于单测锁死分支。

---

## 二、分片并发（对账）

### 2.1 骨架

```
ReconcileService.run(date)
   │
   ├─ 取充值记录 / 账本聚合 / 链上孤儿（同一窗口）
   │
   ├─ ReconcileShardExecutor
   │     ├─ shard = floorMod(userId.hashCode(), 8)     ← N=8
   │     ├─ 每个 shard 独立线程池 core=2                ← 16 线程上限
   │     └─ 全局 Guava RateLimiter 50 QPS               ← 所有 shard 共享
   │
   ├─ CompletableFuture 并发分类（纯计算，不写库）
   │
   └─ 主线程统一 DELETE + INSERT reconcile_diff
```

配置：

```yaml
web3:
  reconcile:
    shards: 8
    shard-threads: 2
    qps: 50
```

### 2.2 为什么这样分

- **按 user_id 分片**：同一用户永远落在同一片，天然避免同一用户的记录被两个线程同时处理；
- **每片独立线程池**：某一片阻塞不影响其他片；
- **全局限流**：50 QPS 是对从库的总压力上限，不是每片 50；
- **分类与落库分离**：并发只做纯计算，`reconcile_diff` 由主线程顺序重建，避免并发写同一张表。

### 2.3 只读从库（未完成）

计划要求“数据源指向只读从库”，本次**没有接第三个 DataSource**：
`ReconcileQueryMapper` 目前仍走业务连接池。

补齐方式（与账本池隔离同款做法）：

1. 增加 `spring.datasource.reconcile.*`（默认同库，生产填从库地址）；
2. 新建 `reconcileDataSource` + `reconcileSqlSessionFactory`；
3. 把 `ReconcileQueryMapper` 移到独立包（如 `com.ray.web3.reconcile.mapper`），
   用 `@MapperScan(sqlSessionFactoryRef = "reconcileSqlSessionFactory")` 扫描；
4. `ReconcileDiffMapper` 留在业务池（它负责写差异表）。

在未接从库前，分片 + 50 QPS 限流仍然生效，只是压力落在主库。

---

## 三、Reorg 回滚

### 3.1 两条路径必须区分

| 路径 | 现状 | 本日补的是什么 |
|---|---|---|
| 实时链路 `isRemoved()` | 已有：撤销日志直接丢弃，不写库 | —— |
| **已入库行回退** | **原本没有** | `DepositReorgService` + `DepositReorgScanner` |

实时链路只能阻止“新到的撤销日志”，挡不住“昨天已入账、今天被重组掉”的行。

### 3.2 机制

```
DepositReorgScanner（默认关闭，reorg-enabled=true 时启用）
   │
   ├─ 取最近 N 块内 status=CREDITED 的充值
   ├─ 按 txHash 查 receipt：
   │     receipt 为空            → 日志已消失
   │     receipt 存在但无该 logIndex → 日志被重组
   ├─ markReorged(depositNo)
   │     ① 先写冲正分录（REVERSE，原分录不动）
   │     ② 再把 deposit_record 置 INVALID
   └─ RPC 异常 → 跳过本轮，不判重组（宁可晚回滚，不可误回滚）
```

顺序不能反：先冲正再置 INVALID。冲正失败时状态保留 CREDITED，下一轮还能重试；
若先置 INVALID，冲正失败就再也不会被扫描到。

### 3.3 演练记录（2026-11-24）

**环境**：Anvil（本地链，head=0）+ MySQL + 应用（`deposit.reorg-enabled=true`，扫描间隔 10s）。

> 说明：本轮没有部署 ERC-20 合约，无法走真实的链上 Transfer → 充值链路。
> 采用“已确认充值 + 链上查不到 receipt（伪造 txHash）”的方式模拟“交易被重组掉”，
> 对 scanner 而言与真实重组等价：receipt 不存在 → 判定日志消失。

**构造**：

- `REORG-1`：status=CREDITED、block=50、txHash 不存在于链上、账本已入账 0.5 USDT；
- `REORG-2`：status=INVALID、账本已入账 0.5、**未冲正**（用于验证对账归类）；
- `PREC-20261124-1`：之前的精度差样例（伪造 txHash），会被同一轮扫描一并回滚，属预期副作用。

**结果 ①：已确认充值被回滚 → INVALID + 冲正**

```sql
SELECT deposit_no,status,block_number FROM deposit_record WHERE deposit_no='REORG-1';
-- REORG-1 | INVALID | 50

SELECT biz_no,biz_type,entry_no,reverse_of FROM ledger_entry
WHERE biz_no='REORG-1' OR reverse_of=31;
-- REORG-1          | DEPOSIT | LE20261124215806823000000 | NULL
-- REV:LE2026112... | REVERSE | LE20261124215831323000006 | 31
```

**结果 ②：对账归“重组回滚/在途”，不是短款**

```sql
SELECT biz_no,diff_type,report_group,direction,delta
FROM reconcile_diff WHERE report_date='2026-11-24';
-- REORG-2 | REORG | IN_TRANSIT | NULL | +0.500000000000000000
```

注意：`delta` 是正的（账上比链上多），如果按长短款口径会被判“长款”；
但 `report_group=IN_TRANSIT`、`direction=NULL`，**不进入长短款报表，不触发熔断**。

**结果 ③：下一轮对账自动收敛**

`REORG-1` 已冲正，重新对账时不再出现在 `reconcile_diff`：

```
POST /api/reconcile/run?date=2026-11-24
→ total=1（只剩未冲正的 REORG-2）
```

**结果 ④：两条路径区分**

- 实时链路：`isRemoved()` 仍然只负责丢弃新到的撤销日志，不碰库；
- 本次新增：scanner 负责“已入库行”的回滚，补上了原来的缺口。

### 3.4 完整 Anvil 合约级演练（未执行，步骤留存）

本轮缺少合约部署链路，以下是完整演练步骤，部署好 MTK 后按此执行：

```bash
# 0) 启动 Anvil，部署 MTK 到 0x5FbDB...（默认账号 0 的第一笔交易）
anvil --port 8545
forge create ... --rpc-url http://127.0.0.1:8545 \
  --private-key 0xac0974...ff80

# 1) 记录快照，制造一笔充值
SNAP=$(cast rpc evm_snapshot --rpc-url http://127.0.0.1:8545)
cast send 0x5FbDB... "transfer(address,uint256)" <用户地址> 1000000 \
  --rpc-url http://127.0.0.1:8545 --private-key 0xac0974...ff80
cast rpc evm_mine --rpc-url http://127.0.0.1:8545

# 2) 触发补拉/实时链路，确认 deposit_record=CREDITED 且账本已入账
curl -X POST "http://127.0.0.1:8080/api/chain/logs/transfer/backfill/0x5FbDB..." ...

# 3) 回到快照（交易消失 = 浅块重组）
cast rpc evm_revert $SNAP --rpc-url http://127.0.0.1:8545

# 4) 等 reorg scanner（默认 60s）→ 校验：INVALID + 冲正 + 对账归在途
```

新版 Anvil 支持 `anvil_reorg`，可直接指定重组深度；旧版用 `evm_snapshot/evm_revert`。
两种方式验证的四件事与本轮模拟一致。

---

## 四、代码位置

| 文件 | 职责 |
|---|---|
| `withdraw/InitRecoveryPolicy` | INIT 三态纯函数判定 |
| `service/WithdrawInitRecoveryService` + `impl` | 取链上事实、回填状态 |
| `scanner/WithdrawInitRecoveryScanner` | INIT 周期接管（默认 60s） |
| `mapper/WithdrawRecordMapper` | `markInitSuccess/markInitFailed/touchInit` |
| `service/DepositReorgService` + `impl` | 回滚：冲正 + INVALID |
| `scanner/DepositReorgScanner` | 最近窗口重组检测（默认关闭） |
| `mapper/DepositRecordMapper` | `selectByDepositNo/selectCreditedSince/markInvalid` |
| `service/impl/ReconcileShardExecutor` | 8 分片 × core2 + 50 QPS |
| `service/impl/ReconcileServiceImpl` | 分类并发化，落库保持单线程 |

---

## 五、测试

| 测试 | 用例数 | 覆盖 |
|---|---|---|
| `InitRecoveryPolicyTest` | 6 | 上链成功/失败、被顶替、无 hash 占用、nonce 消耗、池内未见 |
| `DepositReorgServiceImplTest` | 3 | 先冲正再 INVALID、无分录仍回滚、终态不处理 |
| `ReconcileServiceImplTest` | 2 | 分片并发下六类分类与净差不变、重跑幂等 |

全部通过。

---

## 六、遗留

1. **只读从库未接**：`ReconcileQueryMapper` 仍走业务池；分片与限流已生效，缺第三个 DataSource；
2. **INIT 自动接管未做真实链上演练**：逻辑与单测完成，需在 Anvil/测试网造一笔广播超时验证；
3. **Reorg 完整合约级演练未执行**：本轮用伪造 txHash 模拟 receipt 消失，scanner 侧等价；
4. **毒消息 DLQ**（上一日遗留）：消费端仍是无限重试，会阻塞分区；
5. **差异处置闭环**：`reconcile_diff.status` 目前只有 OPEN，补记/冲正/核销的动作未接。
