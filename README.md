# web3-backend

> 基于 **Web3j 6 + Infura** 的以太坊后端骨架，面向钱包 / 充值提币 / 链上数据查询场景。
> 已提供 13 个链上查询/补拉/充值配置 REST 接口、区块扫描进度落库、ERC-20 Transfer 事件解码入库（断点续传 + 幂等）、WebSocket
> （`eth_subscribe`）实时区块/转账监听，以及充值业务闭环（热钱包匹配 → 幂等入账 → Redis 通知下游）。

- JDK 21 + Spring Boot 4.1.2 + Spring 7.0.10 + MySQL/Redis + MyBatis-Plus + Web3j 6.1.0 + Infura
- 技术选型详解见 [`docs/技术选型.md`](docs/技术选型.md)

---

## 一、功能特性

### 已实现
- **链上查询 REST API**：客户端版本 / 链 ID / 最新区块高度 / Gas Price / 地址余额（wei + ether）/ 分页拉取合约日志 / 按交易哈希解码转账
- **历史 Transfer 日志补拉（backfill）**：以 `block_sync_state.latest_scanned_block` 为游标**断点续传**，按页（默认 1000 块/页）拉取→解码→落库，
  每页"事件写入 + 游标推进"同事务提交，唯一键 `INSERT IGNORE` 保证重放不产生重复流水（详见 [5.7](#57-历史补拉backfill与游标断点续传)）
- **Transfer 事件精确过滤与解码**：`eth_getLogs` 只带 `topic[0] = Transfer(address,address,uint256)`，非 Transfer 日志丢弃；
  金额按各合约 `decimals()` 动态换算（USDT 6 / ETH 18），`displayValue` 与 Etherscan 展示一致
- **节点限制自适应**：单段命中 Infura `more than 10000 results` 时自动缩小跨度续拉（优先采纳节点建议区间，否则二分，16 块护栏）
- **WebSocket 实时订阅**（`web3.websocket.enabled=true` 时生效）
  - `newHeads`：新区块头推送（高度、hash、gasUsed、时间）
  - `logs`：按合约 + `Transfer` topic 过滤的转账事件推送
  - 两者都走 `eth_subscribe`，回调在 WebSocket 读线程执行，`CompositeDisposable` 统一退订
- **区块扫描器**：轮询最新区块高度并把进度落库到 `block_sync_state`，Redisson 分布式锁保证多实例仅一个节点执行
- **RPC 容错**：Resilience4j 两套实例——查询接口 `rpc`，分页拉日志 `rpc-segment`（按单段粒度重试 + 指数退避 + 限流 5 QPS）
- **代理可配置**：HTTP RPC 经 OkHttp 代理；wss 经自建 HTTP CONNECT 隧道（详见 [5.5](#55-代理穿透http-rpc--wss)）
- **统一响应与异常处理**：`ApiResponse` 封装 + `GlobalExceptionHandler`（RPC 类异常统一转 `502`）
- **API 文档**：SpringDoc OpenAPI 3.1（`/swagger-ui.html`、`/v3/api-docs`）
- **可观测性**：Actuator + Micrometer Prometheus
- **离线单元测试**：13 个 JUnit 5 + Mockito 用例（不依赖真实节点/DB/Redis，覆盖模块四充值路由、热钱包匹配与参数校验口径），`mvn test` 全绿
- **充值业务路由与落库（模块四）**：实时/补拉解出的 Transfer 事件按 `to` 地址比对平台热钱包与用户充值地址（配置中心 + `deposit_wallet` 表），
  命中则以 `(chain_id, txHash, logIndex)` 唯一键幂等写入 `deposit_record`（状态**待确认**），随后 `PUBLISH deposit_channel {json}` 通知下游账务（详见 [5.11](#511-充值业务路由与落库模块四)）

### 规划中
- 提币服务、多链扩展、托管钱包；充值确认推进（`PENDING_CONFIRM → CREDITED`）与 `notify_status=FAILED` 的定时重发补偿

---

## 二、技术栈

| 层面 | 选型 | 版本 | 职责 |
|---|---|---|---|
| 语言/运行时 | JDK | 21 (LTS) | Boot 4 基线为 Java 17，本项目锁 21 |
| 应用框架 | Spring Boot | 4.1.2 | Web 容器、DI、配置管理（Spring 7.0.10 / Tomcat 11.0.27） |
| 链交互库 | Web3j | 6.1.0 | JSON-RPC 调用、ABI、事件订阅 |
| JSON | Jackson 3（`tools.jackson`）+ Jackson 2 双栈 | 3.1.7 / 2.21.7 | Boot 4 默认 Jackson 3；web3j 6 亦基于 Jackson 3，二者共享 `jackson-annotations` 2.21 |
| 链节点接入 | Infura | — | 托管 RPC 节点（HTTP + WebSocket） |
| 构建工具 | Maven | 3.9.x | 依赖管理与打包 |
| ORM | MyBatis-Plus | 3.5.18（`spring-boot4-starter`） | 扫描进度等持久化 |
| 数据库 | MySQL | 8.x | `block_sync_state`、流水表 |
| 缓存 | Redis | 7.x | 热点缓存、分布式协调 |
| 分布式锁 | Redisson | 4.8.0 | 区块扫描互斥、提币防重 |
| RPC 容错 | Resilience4j | 2.4.0（`resilience4j-spring-boot4`） | 重试 + 熔断，需配合 `spring-boot-starter-aspectj` |
| 任务调度 | Spring Scheduler + XXL-Job | 3.5.0（可选） | 扫块触发 |
| API 文档 | SpringDoc OpenAPI | 3.2.0 | Swagger UI |
| 监控 | Actuator + Micrometer + Prometheus | Boot BOM | 指标暴露 |
| 编码简化 | Lombok | Boot BOM | `@Data`/`@Slf4j`/`@RequiredArgsConstructor` |

> **Boot 4 迁移注意**：`spring-boot-starter-aop` 已被移除，切面依赖改用 `spring-boot-starter-aspectj`；
> MyBatis-Plus 与 Resilience4j 必须换成 `-spring-boot4` 系列 starter，否则 parent 里找不到版本坐标。

---

## 三、架构与目录结构

### 3.1 分层架构

```
┌──────────────────────────────────────────────────────────┐
│  Controller 层  /api/chain/**（SpringDoc 注解）             │
├──────────────────────────────────────────────────────────┤
│  Service 层   ChainService（rpc 重试/熔断）                 │
│              EventLogService + RpcCallService（rpc-segment）│
│              HistoryBackfillService（补拉编排 + Redisson 互斥）│
│              SyncCursorService / TransferEventStoreService  │
│              DepositWalletService + DepositRoutingService（模块四）│
├───────────┬──────────────────┬───────────────────────────┤
│ web3j     │ wsWeb3j          │ Scanner（Scheduler/XXL-Job  │
│ (HTTP RPC │ (WebSocket 长连接 │  + Redisson 锁)            │
│  OkHttp)  │  newHeads/logs)  │  区块轮询 → 进度落库         │
├───────────┴──────────────────┴───────────────────────────┤
│  持久层  MyBatis-Plus → MySQL（block_sync_state /          │
│         transfer_event_log，游标与事件同事务；             │
│         deposit_wallet / deposit_record 充值闭环）        │
│  缓存/锁 → Redis（游标缓存 + Redisson 锁 + deposit_channel）  │
├──────────────────────────────────────────────────────────┤
│  可观测  Actuator + Prometheus     文档  Swagger UI         │
└──────────────────────────────────────────────────────────┘
```

两个 `Web3j` 实例并存：`web3j`（HTTP，`Web3jConfig`）与 `wsWeb3j`（WebSocket，`WebSocketConfig`），
注入 WS 实例时需用 `@Qualifier("wsWeb3j")` 区分。

### 3.2 目录结构

```
web3-backend
├── pom.xml
├── sql/schema.sql                            # MySQL 初始化脚本（四张表）
├── docs/技术选型.md                            # 技术选型详解
└── src/main/java/com/ray/web3
    ├── Web3BackendApplication.java           # 启动类（@EnableScheduling + @MapperScan）
    ├── config/
    │   ├── InfuraProperties.java             # web3.infura.* 配置绑定（含代理项、log-chunk-size）
    │   ├── BackfillProperties.java           # web3.backfill.*（开关/单页跨度/单次上限/游标缓存）
    │   ├── WebSocketProperties.java          # web3.websocket.* 配置绑定（url/代理/订阅开关/合约列表）
    │   ├── DepositProperties.java            # web3.deposit.*（开关/频道/配置侧热钱包/缓存 TTL/确认阈值）
    │   ├── Web3jConfig.java                  # HTTP Web3j Bean（OkHttp 可选代理）
    │   ├── WebSocketConfig.java              # WebSocketService + wsWeb3j Bean（CONNECT 隧道注入）
    │   ├── RedisConfig.java                  # RedisTemplate 序列化（Jackson 3）
    │   ├── DepositConfig.java                # 注册 DepositProperties
    │   └── XxlJobConfig.java                 # XXL-Job 执行器（xxl.job.enabled=true 才加载）
    ├── consts/EventConsts.java               # ERC-20 Transfer 事件定义（ABI 编码用）
    ├── consts/PatternConsts.java             # 地址/交易哈希校验正则（各 Service 共用）
    ├── consts/DbConsts.java                  # 批量入库行数常量（全项目单一口径）
    ├── controller/ChainController.java       # 链上查询 + 历史补拉 + 充值地址配置 REST API（13 个端点）
    ├── listener/ChainEventListener.java      # WS 实时订阅（newHeads + logs）
    ├── service/
    │   ├── ChainService.java                 # 链上查询接口
    │   ├── EventLogService.java              # eth_getLogs 分页拉取 + 单页拉取接口
    │   ├── RpcCallService.java               # 单段 RPC 调用（承载重试/熔断/限流注解）
    │   ├── TokenMetadataService.java         # 代币精度 decimals() 读取与缓存
    │   ├── SyncCursorService.java            # 补拉游标读写（MySQL 事实 + Redis 缓存）
    │   ├── TransferEventStoreService.java    # 分页事件落库 + 游标同事务推进
    │   ├── DepositWalletService.java         # 热钱包/用户充值地址匹配（内存索引 + TTL 回源）
    │   ├── DepositRoutingService.java        # 充值路由：匹配 → 幂等入账 → Redis 发布
    │   ├── HistoryBackfillService.java       # 历史补拉编排入口
    │   └── impl/
    │       ├── ChainServiceImpl.java         # Web3j 调用 + @Retry/@CircuitBreaker("rpc")
    │       ├── EventLogServiceImpl.java      # 分段循环 + 自适应缩跨度 + 日志解码
    │       ├── RpcCallServiceImpl.java       # @Retry/@CircuitBreaker/@RateLimiter("rpc-segment")
    │       ├── TokenMetadataServiceImpl.java # 链上读精度并缓存
    │       ├── SyncCursorServiceImpl.java    # 游标 upsert/回源 + 缓存降级
    │       ├── TransferEventStoreServiceImpl.java # INSERT IGNORE 分批写入
    │       ├── DepositWalletServiceImpl.java     # 配置+DB 并集的小写地址索引
    │       ├── DepositRoutingServiceImpl.java    # 待确认入账 + deposit_channel 发布
    │       └── HistoryBackfillServiceImpl.java # 逐页推进 + Redisson 互斥
    ├── scanner/BlockScanner.java             # 区块扫描器（双触发 + 分布式锁）
    ├── mapper/
    │   ├── BlockSyncStateMapper.java         # 游标 upsert（GREATEST 单调前移）/ 回读
    │   ├── TransferEventLogMapper.java       # INSERT IGNORE 批量写 + 对账计数
    │   ├── DepositWalletMapper.java          # 启用充值地址全量加载（供缓存）
    │   └── DepositRecordMapper.java          # 充值记录 INSERT IGNORE + 幂等回读 + 通知状态更新
    ├── model/
    │   ├── entity/BlockSyncState.java        # 扫描进度/补拉游标实体
    │   ├── entity/TransferEventLog.java      # Transfer 流水实体
    │   ├── entity/DepositWallet.java         # 充值地址配置实体
    │   ├── entity/DepositRecord.java         # 充值记录实体（状态待确认）
    │   ├── DepositConsts.java                # 地址类型/充值状态/来源/通知状态常量
    │   ├── dto/TransferEventDTO.java         # 解码后的 Transfer 事件
    │   └── vo/                               # ApiResponse / ChainStatusVO / BalanceVO / TransferLogVO / BackfillResultVO / DepositEventVO
    ├── util/
    │   ├── ChainParams.java                  # 入参校验（合约地址/交易哈希/区块区间，统一抛 IllegalArgumentException）
    │   ├── ChainQueryUtils.java              # 无状态链上查询封装（最新区块高度 + 异常转译）
    │   ├── ProxyTunnel.java                  # HTTP CONNECT 隧道 + TLS 升级（wss 代理穿透）
    │   └── TransferEventDecoder.java         # topic[0] 计算 + 日志→TransferEventDTO 解码
    └── exception/GlobalExceptionHandler.java # 校验/参数/RPC/交易异常统一响应
```

---

## 四、REST API

基础路径：`/api/chain`，统一响应结构 `ApiResponse<T>{ code, message, data }`（`code=0` 表示成功）。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/chain/status` | 链上状态：客户端版本 / 链 ID / 最新区块高度 / Gas Price |
| GET | `/api/chain/block-number` | 最新区块高度 |
| GET | `/api/chain/balance/{address}` | 地址余额，返回 wei 与 ether 两种单位 |
| GET | `/api/chain/logs/transfer/{contract}` | 分段拉取指定合约区间内的 Transfer 日志，`fromBlock` / `toBlock` 为必填 query 参数 |
| GET | `/api/chain/logs/transfer/decoded/{contract}` | 同上但按事件蓝图解码，返回 `from`/`to`/`rawValue`/`displayValue`（精度取合约 `decimals()`） |
| GET | `/api/chain/tx/{txHash}/transfers` | 按交易收据解码本笔交易内全部 Transfer（多币种逐个取精度） |
| POST | `/api/chain/logs/transfer/backfill/{contract}` | **历史补拉（断点续传）**：游标之后分页拉取并落库，可选 `chunkSize` query 参数临时调小单页跨度 |
| GET | `/api/chain/logs/cursor/{contract}` | 查询该合约补拉游标 `lastProcessedBlock`（Redis 命中优先，未命中回源 MySQL，无记录返回 null） |
| GET | `/api/chain/logs/transfer/saved-count/{contract}` | 对账：统计区间内已入库 Transfer 条数，与链上拉取条数比对确认不漏单 |
| GET | `/api/chain/deposit/wallets` | 当前路由生效中的充值地址配置（配置中心热钱包 + `deposit_wallet` 表的并集） |
| POST | `/api/chain/deposit/wallets` | 新增/更新充值地址（JSON 体），按 `(chainId, 小写地址)` upsert，写完立即失效地址缓存 |
| DELETE | `/api/chain/deposit/wallets/{address}` | 停用充值地址（`status=0`，历史充值记录保留）；`chainId` 为空取当前链 |
| POST | `/api/chain/deposit/wallets/refresh` | 强制回源重建地址内存索引（新增/停用后无需等 TTL），返回生效地址数 |

**示例**

```bash
curl http://localhost:8080/api/chain/block-number

curl http://localhost:8080/api/chain/balance/0x你的地址

# 拉取 USDT 合约两个区块之间的 Transfer 日志（单段跨度受 web3.infura.log-chunk-size 控制）
curl "http://localhost:8080/api/chain/logs/transfer/0xdac17f958d2ee523a2206206994597c13d831ec7?fromBlock=26297100&toBlock=26297101"

# 历史补拉：18000000 → 18001000（已跑过则从游标续拉，全量覆盖则直接返回 skipped=true）
curl -X POST "http://localhost:8080/api/chain/logs/transfer/backfill/0xdac17f958d2ee523a2206206994597c13d831ec7?fromBlock=18000000&toBlock=18001000"

# 对账：已入库条数与游标
curl "http://localhost:8080/api/chain/logs/transfer/saved-count/0xdac17f958d2ee523a2206206994597c13d831ec7?fromBlock=18000000&toBlock=18001000"
curl  "http://localhost:8080/api/chain/logs/cursor/0xdac17f958d2ee523a2206206994597c13d831ec7"

# 充值地址：登记一个限定 USDT 合约的平台热钱包，然后查看生效配置
# contract 留空则任意代币到账均计为该地址充值；walletType=USER 时必须带 userId
curl -X POST http://localhost:8080/api/chain/deposit/wallets \
  -H "Content-Type: application/json" \
  -d '{"address":"0x0fD04a68d3c3A692d6Fa30384D1A87Ef93554eE6","walletType":"HOT","currency":"USDT","contract":"0xdac17f958d2ee523a2206206994597c13d831ec7"}'
curl http://localhost:8080/api/chain/deposit/wallets

# 下游订阅验证：另一个终端先 SUBSCRIBE，命中热钱包的转账入库后会收到一条 PENDING_CONFIRM 消息
redis-cli SUBSCRIBE deposit_channel
```

**响应示例**

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "clientVersion": "Geth/v1.17.8-stable/linux-arm64/go1.27.1",
    "chainId": 1,
    "latestBlock": 26297151,
    "gasPrice": 42289376
  }
}
```

`TransferLogVO` 字段：`blockNumber`、`transactionHash`、`logIndex`、`contractAddress`、`from`、`to`、`value`。

`TransferEventDTO` 字段：`transactionHash`、`blockNumber`、`logIndex`、`from`、`to`、`rawValue`（最小单位整数）、`displayValue`（按精度换算）。

`BackfillResultVO` 字段：`chainId`、`contractAddress`、`requestFrom`/`requestTo`（原始入参）、`fromBlock`/`toBlock`（实际执行区间，`skipped=true` 时为 null）、
`cursorBefore`/`cursorAfter`、`chunkSize`、`pages`、`transferCount`（本页链上事件总数）、`savedCount`（本次新入库数，重放时为 0）、
`truncated`（`toBlock` 是否被链高截断）、`limited`（是否被 `max-blocks-per-run` 限幅）、`skipped`（游标已覆盖请求区间）、`elapsedMs`。

**补拉响应示例**（主网 USDT 实跑结果）

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "chainId": 1,
    "contractAddress": "0xdac17f958d2ee523a2206206994597c13d831ec7",
    "requestFrom": 18000000, "requestTo": 18001000,
    "fromBlock": 18000000, "toBlock": 18001000,
    "cursorBefore": 17999999, "cursorAfter": 18001000,
    "chunkSize": 1000, "pages": 2,
    "transferCount": 16027, "savedCount": 16027,
    "truncated": false, "limited": false, "skipped": false, "elapsedMs": 7166
  }
}
```

在线文档：`http://localhost:8080/swagger-ui.html`，OpenAPI 描述：`http://localhost:8080/v3/api-docs`

---

## 五、核心模块说明

### 5.1 链上交互（Web3j + Infura）
- `Web3jConfig` 基于 `HttpService(infura.http-url)` 构建 HTTP 单例 `web3j`，`destroyMethod = "shutdown"` 保证退出时释放 OkHttp 连接池；连接在首次请求时才建立，无 `projectId` 不阻塞启动。
- `WebSocketConfig` 构建 `WebSocketService` + `wsWeb3j`，供 `ChainEventListener` 做实时订阅。
  `new WebSocketService(client, true)` 的 `includeRawValues=true` 表示事件参数为**原始 hex**（与 HTTP `eth_getLogs` 结构一致），需要自行 `Numeric` 解码。
- Infura Project ID 通过环境变量 `INFURA_PROJECT_ID` 注入，**不写入代码库**。
- 默认对接主网（`chainId=1`）；切测试网改 `web3.infura.*` 与 `web3.websocket.url` 即可（配置文件中已给出 Sepolia / 本地 Anvil 的注释行）。
- `web3.infura.ws-url` 已从配置文件移除：WS 地址统一由 `web3.websocket.url` 提供，`InfuraProperties.wsUrl` 为暂无消费方的遗留字段。

### 5.2 RPC 容错（Resilience4j）
- `ChainServiceImpl` 各方法标注 `@Retry(name = "rpc")` + `@CircuitBreaker(name = "rpc")`；
  `RpcCallServiceImpl.ethGetLogs` 标注 `@Retry/@CircuitBreaker/@RateLimiter(name = "rpc-segment")`，使分页拉取**按单段粒度重试**，不重跑整个拉取过程。
- `rpc`：最多 3 次（间隔 500ms）；`rpc-segment`：最多 5 次 + 指数退避（×2）+ 随机抖动（因子 0.5），
  并用 `RateLimiter` 主动平滑为 5 次/秒（等待上限 5s）避免打爆 Infura 配额拿 429。
- 熔断策略：滑动窗口 20、最小调用数 5、失败率 ≥ 50% 熔断 30s、半开放行 5 次。
- 兜底由 `GlobalExceptionHandler` 完成：`IllegalStateException`（RPC/节点错误）→ `502`，`TransactionException` → `500`，参数非法 → `400`。

### 5.3 区块扫描（Scheduler + XXL-Job + Redisson）
- `BlockScanner.doScan` 取最新区块高度 → `selectOne` 查进度 → 无记录 `insert`、有记录 `updateById`，写入 `block_sync_state`。
- **触发通道现状**：`@Scheduled(fixedDelayString = "${web3.scanner.interval-ms:30000}")` 目前**处于注释状态**，`scanByXxlJob`（`@XxlJob("blockScanHandler")`）是唯一入口；
  而 `xxl.job.enabled` 默认 `false`，因此本地默认配置下扫描器不会自动跑，需要打开 `@Scheduled` 或接入 XXL-Job 调度中心。
- **Redisson `tryLock`** 保证多实例仅一个节点执行，未抢到锁直接跳过本轮；释放前判 `isHeldByCurrentThread()`。

### 5.4 WebSocket 实时订阅（ChainEventListener）
- `web3.websocket.enabled=true` 时加载；`ApplicationReadyEvent` 后启动订阅，`subscribe-new-heads` 控制是否订 `newHeads`。
- 用的是 **`newHeadsNotifications()` / `logsNotifications(addresses, topics)`**，即 `eth_subscribe` 服务端推送；
  **不要**混用 `ethLogFlowable(filter)`——后者是 `eth_newFilter` + 每 15s 轮询 `eth_getFilterChanges`，与 WebSocket 无关。
- `logsNotifications` 内部 `createLogsParams` 会对入参直接调 `isEmpty()`，**传 null 会 NPE**，因此 `watch-contracts` 为空时传空集合（表示监听全部合约，量大慎用）。
- `websocket.events.Log` 的字段是原始 hex 字符串（不同于 `core.methods.response.Log` 已转 `BigInteger`），块高需 `Numeric.toBigInt` 解码。
- 资源释放顺序：`@PreDestroy` 先 `disposables.dispose()`（发 `eth_unsubscribe`）→ `webSocketService.close()`；`wsWeb3j.shutdown()` 由 Bean `destroyMethod` 在这之后执行。
- 局限：`eth_subscribe("logs")` 只按 address + topics 过滤，无区块区间概念，也不回报链重组，故监听只做"提前发现"，落库仍以区间重扫为准。

### 5.5 代理穿透（HTTP RPC + wss）
Java 客户端默认不读系统代理，本机开 Clash/v2ray 时会出现"curl 能连、Java 超时"。两条通道分别处理：

**HTTP RPC**（`Web3jConfig`）：OkHttp 显式设 `Proxy(HTTP)`。

```yaml
web3:
  infura:
    proxy-host: ${WEB3_PROXY_HOST:127.0.0.1}
    proxy-port: ${WEB3_PROXY_PORT:7890}
```

**WebSocket**（`WebSocketConfig` + `util/ProxyTunnel`）：web3j 的 WS 底层是 **Java-WebSocket**（不是 OkHttp），
其 `WebSocketClient` 默认 `Proxy.NO_PROXY`，既不读系统代理也不读 `-Dhttp.proxyHost/-Dhttps.proxyHost`，
`setProxy(Proxy.Type.HTTP)` 也不会真的发 CONNECT，因此必须自建隧道：

1. 向代理 socket 写 `CONNECT mainnet.infura.io:443 HTTP/1.1`，逐字节读到 `\r\n\r\n` 校验 `200`；
2. 用 `SSLSocketFactory.createSocket(plain, host, port, true)` 升级为 TLS 并做 SNI/主机名校验；
3. `WebSocketClient#setSocket(sslSocket)`（已废弃但为复用已连 socket 的唯一入口）注入后再 `service.connect()`。

```yaml
web3:
  websocket:
    proxy-host: ${WEB3_PROXY_HOST:127.0.0.1}   # 与 HTTP RPC 共用同一对环境变量
    proxy-port: ${WEB3_PROXY_PORT:7890}
```

- `ws://`（本地 Anvil 等）或未配代理 → 直连，`wss` 未配代理时打 WARN 提醒。
- 生产海外服务器通常两项都留空直连；建议用环境变量注入，避免把 `127.0.0.1:7890` 写死。

### 5.6 eth_getLogs 分页与节点上限自适应（EventLogServiceImpl）
- 校验合约地址格式与区块区间 → 用 `ethBlockNumber` 截断超出链上高度的 `toBlock` → 按 `web3.infura.log-chunk-size` 分段循环调用 `eth_getLogs`。
- **topic 精确过滤**：`buildFilter` 用 `addSingleTopic(TransferEventDecoder.transferEventTopic())`，只拉 `Transfer` 日志；
  topic[0] 由 `EventEncoder.encode(EventConsts.TRANSFER_EVENT)` 算出，与 `ChainEventListener` 共用同一个方法（单一来源，避免监听与补拉过滤条件漂移）。
- **自适应缩跨度**（`fetchLogsAdaptive`）：单段命中节点上限时，正则解析错误里的建议区间
  （`query returned more than 10000 results. Try with this block range [0x.., 0x..]`）并优先采纳，建议不合理（不小于本段）则退化为二分；
  跨度已 ≤ `MIN_CHUNK_SPAN`（16 块）仍被拒则原样抛出，不无限递归。非上限类错误（`header not found` 等）不缩跨度，直接交给 `rpc-segment` 重试与熔断。
- 缩跨度只改变请求切分方式、不改变结果集合：各子段日志按区块升序合并后仍是本段完整日志，因此调用方可以安全地把游标推进到本段末尾。
- 两种对外能力：`fetchDecodedTransferLogs`（整区间，内部按 chunk 分段 + 链高截断）与 `fetchTransferLogPage`（**单页**，供补拉逐页推游标，不做链高截断）。
- 解码：`topic[1]` = from、`topic[2]` = to（各取 32 字节 topic 末 20 字节）、`data` = 金额，按 `TokenMetadataService.decimals(contract)` 换算为 `displayValue`；
  精度优先取缓存→`eth_call decimals()` 动态读取→失败时回退已知精度表（USDT/USDC 6、WBTC 8、DAI 18）→仍未知则按 18 处理，
  不因元数据读不到而阻断整页落库（仅记 WARN）。
- 验收数据（主网 USDT 18000000→18001000）：整段 1000 块一次请求命中上限（实际 16027 条 > 10000），
  自适应按节点建议切成 587 + 413 两段续拉，一次补拉全部取回，`savedCount` 与 `transferCount` 一致。

### 5.7 历史补拉（backfill）与游标断点续传
监听器启动前要把停机期间的状态补齐，靠 `HistoryBackfillService` 完成：以游标（`lastProcessedBlock`）为起点按页拉取，不做一次性全量重扫。

**逐页固定顺序**：`fetchTransferLogPage`（拉取+解码）→ `savePageWithCursor`（事件落库 + 游标推进，同事务）→ `refreshCache`（刷 Redis）。
任何一步崩溃都停在“上一个已提交页”，重启后从游标下一页续拉即可补齐；已落库页若被重放由唯一键去重，**既不丢单也不重复入账**。

| 组件 | 职责 | 关键点 |
|---|---|---|
| `SyncCursorService` | 游标读写 | MySQL 为事实来源，Redis（`web3:sync:cursor:{chainId}:{addr}`）只作读加速；地址统一小写归一；Redis 不可用自动降级直读 DB；`upsertCursor` 用 `ON DUPLICATE KEY UPDATE` + `GREATEST` 保证游标**单调前移** |
| `TransferEventStoreService` | 分页落库 | `@Transactional(rollbackFor = Exception.class)`；每 1000 行（`DbConsts.INSERT_BATCH_SIZE`）一批 `INSERT IGNORE`；**空页不发 SQL 但照常推进游标**（否则补齐会卡在没有日志的区间） |
| `HistoryBackfillService` | 编排 | Redisson `tryLock`（`web3:backfill:lock:{chainId}:{addr}`）同合约互斥；`toBlock` 超链高截断；`from = max(startBlock, cursor + 1)`；`max-blocks-per-run` 限幅后下次从游标自动续拉 |

**不变量与失败语义**
- 不漏单：事件与游标同事务，不存在“游标前移了但事件没入库”。
- 不重复：`uk_chain_tx_log (chain_id, transaction_hash, log_index)` + `INSERT IGNORE`，重放时 `savedCount=0`。
- 空日志区间不卡住：无 Transfer 的页也推进游标。
- 某页抛错：事务回滚，游标不前进，整区间重试时从本页重来（已提交的前序页由唯一键去重）。
- 金额列用 `VARCHAR` 而非 `DECIMAL`：MySQL `DECIMAL` 最大精度 65 位，容不下 `uint256`（最多 78 位）；
  监听任意合约时若碰到超大规模金额，`DECIMAL` 写入会整页回滚、游标永久卡住，而字符串写入不会失败。

**验收记录**（主网 USDT，Infura + 本地 MySQL/Redis）

| 场景 | 结果 |
|---|---|
| 首次补拉 18000000→18001000 | `pages=2 transferCount=16027 savedCount=16027 cursorAfter=18001000`；库内 16027 行，DB/Redis 游标均为 18001000 |
| 回退游标到 17999999 重跑同一区间（模拟崩溃后重放） | `fromBlock=18000000 transferCount=16027 savedCount=0`，总数仍 16027，重复组数 0 |
| 请求 18000000→18001050（游标已在 18001000） | 实际从 `fromBlock=18001001` 续拉，新增 783 条，累计 16810 = 16027 + 783 |
| 与独立 RPC 对账 | `eth_getLogs` 直查 18001001..18001050 与 18000000 单块分别得 783 / 45 条，与库内一致 |
| 重复请求已覆盖区间 | `skipped=true`，`pages=0`，游标不变 |

### 5.8 Redis 与序列化
- `RedisConfig` 为 `RedisTemplate<String, Object>` 配置 key=String、value=JSON。
  Boot 4（Spring Data Redis 4）下基于 Jackson 2 的 `GenericJackson2JsonRedisSerializer` 已 `@Deprecated(forRemoval = true)`，现改用 Jackson 3 的 **`GenericJacksonJsonRedisSerializer`**。
- mapper 用 `JsonMapper.builder()` 构建（Jackson 3 的 `ObjectMapper` 不可变）；`WRITE_DATES_AS_TIMESTAMPS` 已从 `SerializationFeature` 移到 `DateTimeFeature`，且 java.time 支持内置于 databind，**不再需要注册 `JavaTimeModule`**。
- 单参构造不启用 default typing（与 Jackson 2 版行为一致），即不写 `@class`，`deserialize` 返回 `LinkedHashMap`；需要具体类型回读时改用 `builder().enableDefaultTyping(ptv)`。
- `RedisTemplate<String, Object>` 这个 Bean 目前仍未被业务代码调用（缓存对象类需求再用）；游标缓存走 Boot 自动配置的 **`StringRedisTemplate`**（游标是纯数字，不需要 JSON 序列化）。
- Redisson 独立使用自己的编解码。

### 5.9 数据模型

`BlockSyncState`（表 `block_sync_state`）记录扫描进度，`contract_address` 非空时 `latest_scanned_block` 同时是该合约的**补拉游标**：

| 列 | 说明 |
|---|---|
| `chain_id` | 扫描的链 ID |
| `contract_address` | 合约地址，空串表示原生链 |
| `latest_scanned_block` | 已处理区块高度（= 补拉 `lastProcessedBlock`，下一轮从 +1 开始） |
| `updated_at` | DB 层 `ON UPDATE CURRENT_TIMESTAMP` |
| `uk_chain_contract` | `(chain_id, contract_address)` 唯一键（`upsertCursor` 的冲突键） |

`TransferEventLog`（表 `transfer_event_log`）记录 ERC-20 Transfer 流水：

| 列 | 说明 |
|---|---|
| `chain_id` / `contract_address` | 链 ID 与代币合约地址（统一小写） |
| `block_number` / `log_index` | 区块高度与日志在区块内的序号 |
| `transaction_hash` | 交易哈希 |
| `from_address` / `to_address` | 转出 / 转入地址 |
| `raw_value` | `VARCHAR(78)`，最小单位整数字符串，容纳 `uint256` 全量 |
| `display_value` | `VARCHAR(80)`，按精度换算后的十进制字符串（保留尾零） |
| `decimals` | 换算所用代币精度（落库时的快照值，事后查链不准） |
| `uk_chain_tx_log` | `(chain_id, transaction_hash, log_index)` 唯一键，`INSERT IGNORE` 的幂等根据 |
| `idx_contract_block` | `(contract_address, block_number)`，对账计数走这个索引 |

`DepositWallet`（表 `deposit_wallet`）是充值路由的地址配置：

| 列 | 说明 |
|---|---|
| `address` | 充值地址（小写），匹配按 `to_address` 等值命中 |
| `wallet_type` | `HOT`=平台热钱包，`USER`=用户专属充值地址 |
| `user_id` | 归属用户，`wallet_type=USER` 时必填 |
| `contract` | 限定代币合约；NULL=任意代币到账均计充值（防币种串账） |
| `status` | 1=启用，0=停用（不再路由新充值，历史记录保留） |
| `uk_chain_address` | `(chain_id, address)` 唯一键，`saveWallet` 的 upsert 冲突键 |

`DepositRecord`（表 `deposit_record`）是充值入账记录（验收口径：命中平台地址即新增一条 `PENDING_CONFIRM`）：

| 列 | 说明 |
|---|---|
| `deposit_no` | 充值单号（`DP` + 时间戳 + 64bit 随机），对外业务主键 |
| `uk_chain_tx_log` | `(chain_id, transaction_hash, log_index)`，**幂等根据**：重连重放/分页重叠/实时与补拉双写只入账一次 |
| `wallet_type` / `user_id` / `currency` | 命中地址的类型、归属与币种 |
| `block_number` | 入账区块高度，确认数 = 链上最新高度 - 此值 |
| `raw_value` / `display_value` / `decimals` | 金额与精度快照（同流水表用 `VARCHAR`，容纳 `uint256`） |
| `status` | `PENDING_CONFIRM`（待确认）/ `CREDITED`（已入账）/ `INVALID`（重组作废） |
| `notify_status` / `notify_at` | 下游发布状态 `INIT`/`SENT`/`FAILED`，`FAILED` 可据此补偿重发 |
| `event_source` | `STREAM`=WebSocket 实时订阅，`BACKFILL`=历史补拉 |

### 5.10 安全与密钥
- 私钥严禁硬编码：本地用环境变量/`.env`（勿提交），生产建议 Vault 或托管钱包（Fireblocks/Turnkey）。
- keystore 文件已在 `.gitignore` 排除（`wallet/`、`*.keystore`）。

### 5.11 充值业务路由与落库（模块四）
把“链上解析出的 Transfer 事件”变成“平台充值记录”，`DepositRoutingService.route(events, source)` 三步一体；
实时链路（`TransferStreamEngine` 工作线程）与补拉链路（`HistoryBackfillServiceImpl` 逐页）均在**落库事务提交后**调用它。

**1. 热钱包匹配**（`DepositWalletService`）
- 地址两个来源取并集：配置中心 `web3.deposit.hot-wallets`（少量平台归集地址）+ `deposit_wallet` 表（用户地址量大/频变）；
  **同地址以 DB 为准**，否则运维在库里停用地址会被配置文件兜底“复活”。
- 全量启用地址按小写地址建 **内存 HashMap**，按 `wallet-cache-refresh-ms`（默认 30s）TTL 懒回源；
  匹配不能逐事件打 DB，否则实时链路落库线程会被 SELECT 拖垮。`POST /api/chain/deposit/wallets/refresh` 可强制回源。
- `contract` 非空时额外限定代币合约（大小写不敏感等值），避免一个地址多币种串账。
- 回源失败沿用上一份缓存并退避重试，缓存层故障不拖垮充值链路；首启就失败则为空索引（不产生充值，而不是错配）。

**2. 幂等保证**
- 写入以 `INSERT IGNORE` + 唯一键 `(chain_id, transaction_hash, log_index)` 进 `deposit_record`：重连重放、
  补拉分页重叠、实时与补拉双写都只新增一行；`deposit_no` 另有唯一键，而 `INSERT IGNORE` 会把“单号重复”也当作重复行静默丢弃，
  所以单号必须用 64bit 随机量保证结构上不可能碰撞。
- 新增行统一按幂等键回读一次：既拿到自增 id（`notify_status` 需按行更新），也过滤掉被 IGNORE 的重复行。
- 多实例并发路由同一笔事件时 `INSERT IGNORE` 仍可能抛唯一键冲突：逐行回读，金额/地址一致则当正常入账，
  不一致则 ERROR 告警等人工裁决（**不自动覆盖**，同一幂等键不同金额属链上异常）。

**3. 入账触发（下游通知）**
- 先落库后 `PUBLISH deposit_channel {json}`（载荷 `DepositEventVO`，走 `StringRedisTemplate.convertAndSend`，消息体为可直接阅读的 JSON 字符串）。
  反过来“先发布后落库”会让落库失败时下游收到一笔不存在的充值，账务对不平。
- 只有“真正新增”的行才发布；发布成功置 `notify_status=SENT`，失败置 `FAILED`（充值记录已提交，不受影响），可据此补偿重发。
- 新记录状态固定 `PENDING_CONFIRM`（待确认）；`confirm-threshold`（默认 12 块）随消息下发供下游判断最终确定性，
  确认推进（`CREDITED`）与 `FAILED` 重发属后续任务，本期只留字段与常量，不在路由环节做链上高度回查。
- 路由异常在两条链路上都单独兜住（只记 ERROR）：账务侧故障不能中断实时订阅入队，也不能打断整段补拉留下游标空洞；
  缺失部分由补拉重跑区间自然补齐（幂等）。

### 5.12 重复口径收拢（`PatternConsts` / `ChainParams` / `ChainQueryUtils`）
地址正则、区块区间校验、取最新块高原先在 3~4 个 Service 里各写一份，现已集中；新增接口直接复用，不再复制粘贴：

| 位置 | 提供 | 说明 |
|---|---|---|
| `consts/PatternConsts` | `ADDRESS_PATTERN` / `LOOSE_ADDRESS_PATTERN` / `TX_HASH_PATTERN` 与 `isAddress` / `isLooseAddress` / `isTxHash` | 地址刻意分两口径：严格 `^0x` 给直透节点的入参（`0X` 会被节点当非法 hex，本地早拦省一次 RPC）；`^0[xX]` 容错版给“先 `toLowerCase` 再入库/匹配”的充值地址录入（运维手写常见 `0X`） |
| `util/ChainParams` | `requireContract` / `requireTxHash` / `requireRange` | 取代之前的 `validateContract` / `validateRange` 私有重复方法；校验失败统一抛 `IllegalArgumentException` → `GlobalExceptionHandler` 转 400 |
| `util/ChainQueryUtils` | `latestBlockNumber(Web3j)` | 取代之前的 `queryLatestBlock` 与四处重写的 `ethBlockNumber().send()` 样板；异常统一转 `IllegalStateException`（→ 502） |
| `consts/DbConsts` | `INSERT_BATCH_SIZE = 1000` | 单条 `INSERT IGNORE` 的最大行数，流水表与充值表共用；同时用作充值路由的分片大小。**已从配置移除**（`web3.deposit.route-batch-size` 不再存在）：批大小只受 `max_allowed_packet` 与单行宽度约束，属于容量参数而非业务开关，放进配置只会让各环境取值漂移、排查时多一个变量 |

> 弹性注解仍不能下放到工具类：静态方法上的 `@Retry/@CircuitBreaker` 不会被 AOP 拦截，
> 所以 `ChainServiceImpl.latestBlockNumber()` 依旧靠方法上的 `@Retry("rpc")` 重试，复用工具方法不影响策略生效。

未收拢：`EventLogServiceImpl` 里的 `RESULT_LIMIT_ERROR_PATTERN` / `RANGE_LIMIT_ERROR_PATTERN`（解析节点报错文案里的建议区间）
只服务“自适应缩跨度”一处，搬到公共类会把节点报错格式这个实现细节外泄，所以留在本地。

---

## 六、快速开始

### 6.1 环境要求
| 组件 | 要求 |
|---|---|
| JDK | 21（`JAVA_HOME` 需指向 JDK 21，IDEA 中 Project SDK 同步切换；Boot 4 最低 Java 17） |
| Maven | 3.9.x |
| MySQL | 8.x，执行 `sql/schema.sql` |
| Redis | 7.x（本地无密码时勿配置 `password` 行，含空串，否则 Redisson 仍发 AUTH 报错） |
| Infura 账号 | 创建 Project 获取 `projectId` |

### 6.2 数据库初始化
```bash
mysql -u root -p < sql/schema.sql
```
脚本会建 `web3_backend` 库与四张表：`block_sync_state`（扫描进度/补拉游标）、`transfer_event_log`（Transfer 流水）、
`deposit_wallet`（平台热钱包/用户充值地址）、`deposit_record`（充值入账记录）。
已跑过旧版本脚本的库，补执行 `transfer_event_log`（或模块四新增的两张充值表）建表语句即可。

### 6.3 编译打包（PowerShell）
```powershell
# 注入 Infura 项目 ID
$env:INFURA_PROJECT_ID = "你的projectId"

# 如需经本地代理访问 Infura（HTTP RPC 与 wss 共用这两个变量；本机开 Clash 时需要）
$env:WEB3_PROXY_HOST = "127.0.0.1"
$env:WEB3_PROXY_PORT = "7890"

# 离线单测（不依赖真实节点/DB/Redis）
mvn test

# 打包 / 启动
mvn clean package -DskipTests
java -jar target/web3-backend-1.0.0-SNAPSHOT.jar
```

### 6.4 验证入口
| 入口 | 地址 |
|---|---|
| 链上状态 | `GET http://localhost:8080/api/chain/status` |
| 最新区块 | `GET http://localhost:8080/api/chain/block-number` |
| 地址余额 | `GET http://localhost:8080/api/chain/balance/{address}` |
| 合约日志 | `GET http://localhost:8080/api/chain/logs/transfer/{contract}?fromBlock=&toBlock=` |
| 解码后事件 | `GET http://localhost:8080/api/chain/logs/transfer/decoded/{contract}?fromBlock=&toBlock=` |
| 历史补拉 | `POST http://localhost:8080/api/chain/logs/transfer/backfill/{contract}?fromBlock=&toBlock=` |
| 补拉游标 | `GET http://localhost:8080/api/chain/logs/cursor/{contract}` |
| 入库对账 | `GET http://localhost:8080/api/chain/logs/transfer/saved-count/{contract}?fromBlock=&toBlock=` |
| 充值地址配置 | `GET` / `POST http://localhost:8080/api/chain/deposit/wallets`（停用：`DELETE /deposit/wallets/{address}`） |
| 充值地址刷新 | `POST http://localhost:8080/api/chain/deposit/wallets/refresh` |
| 下游充值消息 | `redis-cli SUBSCRIBE deposit_channel`（入账后收到 `PENDING_CONFIRM` 消息） |
| Swagger UI | `http://localhost:8080/swagger-ui.html` |
| OpenAPI 描述 | `http://localhost:8080/v3/api-docs` |
| 健康检查 | `http://localhost:8080/actuator/health` |
| Prometheus 指标 | `http://localhost:8080/actuator/prometheus` |

启动成功的标志：日志出现 `Tomcat started on port 8080`、（启用 WS 时）`WebSocket 连接成功` 与
`已订阅 newHeads` / `已订阅 Transfer 日志（eth_subscribe logs）`，之后持续输出 `[newHeads]`、`[logs]` 推送。

补拉跑通的标志（以主网 USDT 18000000→18001000 为例）：
`WARN … 分段 [18000000,18000999] 命中节点上限，跨度 1000 → 587 后续拉` → `INFO 补拉分页 …` → `INFO 历史补拉完成 … pages=2`，
且 `SELECT latest_scanned_block FROM block_sync_state WHERE chain_id=1 AND contract_address='0xdac1…'` 与
`redis-cli get web3:sync:cursor:1:0xdac1…` 均等于请求的 `toBlock`。

---

## 七、配置项一览（`application.yml`）

| 配置 | 环境变量 | 默认值 | 说明 |
|---|---|---|---|
| `spring.datasource.url` | `MYSQL_HOST` / `MYSQL_PORT` / `MYSQL_DATABASE` | `localhost:3306/web3_backend` | MySQL 连接 |
| `spring.datasource.username/password` | `MYSQL_USERNAME` / `MYSQL_PASSWORD` | `root` / `root` | 账号密码 |
| `spring.data.redis.host/port/database` | `REDIS_HOST` / `REDIS_PORT` / `REDIS_DATABASE` | `localhost:6379:0` | Redis 连接 |
| `mybatis-plus.*` | — | 下划线转驼峰、`id-type: auto` | ORM 行为 |
| `web3.infura.project-id` | `INFURA_PROJECT_ID` | 空 | Infura Project ID |
| `web3.infura.http-url` | — | 主网 URL | HTTP RPC 地址 |
| `web3.infura.chain-id` | — | 1 | 链 ID（主网=1，Sepolia=11155111） |
| `web3.infura.proxy-host` | `WEB3_PROXY_HOST` | `127.0.0.1` | HTTP RPC 代理主机，留空直连 |
| `web3.infura.proxy-port` | `WEB3_PROXY_PORT` | `7890` | HTTP RPC 代理端口 |
| `web3.infura.log-chunk-size` | `WEB3_LOG_CHUNK_SIZE` | 50 | `eth_getLogs` 单段最大区块跨度（节点限制严时调小到 50/10） |
| `web3.backfill.enabled` | `WEB3_BACKFILL_ENABLED` | true | 是否加载历史补拉服务与 REST 端点（关闭后调补拉接口报“未启用”） |
| `web3.backfill.chunk-size` | `WEB3_BACKFILL_CHUNK_SIZE` | 1000 | 补拉单页区块跨度；命中节点上限时由 `EventLogService` 自动缩跨度，无需手工调小 |
| `web3.backfill.max-blocks-per-run` | `WEB3_BACKFILL_MAX_BLOCKS` | 0（没有单次上限） | 单次请求最多推进区块数，设正数可把大区间拆成多次调用（下次从游标自动续拉，响应 `limited=true`） |
| `web3.backfill.cursor-cache-enabled` | `WEB3_BACKFILL_CURSOR_CACHE` | true | 游标读是否优先走 Redis（关闭后直读 MySQL；Redis 不可用时也自动降级） |
| `web3.scanner.enabled` | — | true | 是否加载区块扫描器 Bean |
| `web3.scanner.interval-ms` | — | 30000 | 扫描间隔（`@Scheduled` 当前被注释，改动生效需打开注解） |
| `web3.websocket.enabled` | `WEB3_WS_ENABLED` | true | 是否建立 WS 连接与订阅 |
| `web3.websocket.url` | — | `wss://mainnet.infura.io/ws/v3/{projectId}` | WS 端点（本地节点用 `ws://127.0.0.1:8545`） |
| `web3.websocket.proxy-host` | `WEB3_PROXY_HOST` | `127.0.0.1` | wss 的 CONNECT 隧道代理主机，留空直连 |
| `web3.websocket.proxy-port` | `WEB3_PROXY_PORT` | `7890` | wss 的 CONNECT 隧道代理端口 |
| `web3.websocket.subscribe-new-heads` | — | true | 是否订阅 `newHeads` |
| `web3.websocket.watch-contracts` | `WEB3_WATCH_CONTRACTS` | USDT 合约 | 监听 Transfer 的合约列表，逗号分隔；留空=全部合约 |
| `web3.deposit.enabled` | `WEB3_DEPOSIT_ENABLED` | true | 是否启用充值路由与落库（关闭后只写流水表，不产生充值记录） |
| `web3.deposit.deposit-channel` | `WEB3_DEPOSIT_CHANNEL` | `deposit_channel` | 下游账务通知的 Redis 频道 |
| `web3.deposit.hot-wallets` | — | 空（示例注释一个地址） | 配置中心热钱包：key=地址，value=币种符号（留空=不限合约）；与 `deposit_wallet` 表取并集，同地址以 DB 为准 |
| `web3.deposit.wallet-cache-refresh-ms` | `WEB3_DEPOSIT_WALLET_TTL_MS` | 30000 | 充值地址缓存回源间隔（路由按内存索引匹配，避免每事件一次 SELECT） |
| `web3.deposit.confirm-threshold` | `WEB3_DEPOSIT_CONFIRM_THRESHOLD` | 12 | 确认阈值（区块数），随消息下发供下游判断最终确定性；0=不设置 |
| `resilience4j.retry/circuitbreaker.instances.rpc` | — | 3 次 / 500ms，窗口 20、阈值 50%、开路 30s | 链上查询容错 |
| `resilience4j.retry/circuitbreaker.instances.rpc-segment` | — | 5 次 / 指数退避 ×2 + 抖动 0.5，熔断同上 | 单段 `eth_getLogs` 容错 |
| `resilience4j.ratelimiter.instances.rpc-segment` | — | 5 次/秒，等待上限 5s | 主动平滑 RPC 吞吐，避免 Infura 429 |
| `management.endpoints.web.exposure.include` | — | `health,info,prometheus,metrics` | Actuator 暴露端点 |
| `xxl.job.enabled` | `XXL_JOB_ENABLED` | false | 是否启用 XXL-Job 执行器 |
| `xxl.job.admin.addresses` | `XXL_JOB_ADMIN_ADDRESSES` | `http://localhost:8088/xxl-job-admin` | 调度中心地址 |
| `xxl.job.accessToken` | `XXL_JOB_ACCESS_TOKEN` | 空 | 调度中心通信令牌 |
| `xxl.job.executor.appname/port/logpath` | — | `web3-backend` / `9999` / `./logs/xxl-job` | 执行器信息 |
| `logging.level.com.ray.web3` | — | debug | 业务日志级别（含 MyBatis SQL） |

---

## 八、常见问题

**Q：启动报 `java.net.ConnectException: Failed to connect to WebSocket`？**
A：wss 需要走代理但没配置。底层 Java-WebSocket 不认系统代理与 JVM 代理参数，必须配 `web3.websocket.proxy-host/proxy-port`（本项目已用 `ProxyTunnel` 建 CONNECT 隧道）。若直连可达（海外服务器）则留空即可。

**Q：启动即崩，`NoClassDefFoundError: com/fasterxml/jackson/annotation/JsonSerializeAs`？**
A：web3j 6 依赖 Jackson 3，而 Jackson 3 databind 需要 `jackson-annotations ≥ 2.21`（`JsonSerializeAs` 在 2.18~2.19 被移除）。Spring Boot 3.5.x 的 BOM 把它压到 2.19.2 就会报这个错——升到 Boot 4.1.x（BOM 给 2.21）即消解，这也是本项目 Boot 与 web3j 必须同升的原因。

**Q：`/api/chain/logs/transfer/...` 返回 502，日志里是 `query returned more than 10000 results`？**
A：已不再需要手工调小跨度——`EventLogServiceImpl` 会解析节点建议区间并自动缩小跨度续拉。若仍然 502，先确认日志里反复出现的“命中节点上限，跨度 … → … 后续拉”最细分到多少块：
一旦跨度已缩到 `MIN_CHUNK_SPAN`（16 块）护栏仍被拒，就不再重试而直接抛出（避免无限递归），说明单块日志量就超限，应拉窄过滤条件（加 address/topic）或缩小请求区间；
非上限类错误（`header not found` 等）不进入自适应分支，按 `rpc-segment` 重试/熔断排查。

**Q：补拉接口反复调用会重复入账吗？**
A：不会。事件写入用 `INSERT IGNORE` + 唯一键 `(chain_id, transaction_hash, log_index)`，重放时 `savedCount=0`；
同时游标由 `upsertCursor` 的 `GREATEST` 保护不会回退。验证方式：把 `latest_scanned_block` 手动调回区间内重跑同一区间，
`transferCount` 不变、`savedCount=0`、`SELECT COUNT(*)` 与重复组数均不变。

**Q：补拉报“合约 … 的补拉任务正在执行中”？**
A：同一 `(chainId, contract)` 的补拉由 Redisson `tryLock` 互斥（不排队、直接失败），避免两个任务重复消耗 RPC 配额。等上一轮结束重试即可。

**Q：补拉报“历史补拉服务未启用（web3.backfill.enabled=false）”？**
A：`web3.backfill.enabled=false` 时服务 Bean 不加载，Controller 用 `ObjectProvider` 延迟取用并抛此错（不影响应用启动），置 `true` 后重启。

**Q：几千上万块的大区间一次跑不完怎么办？**
A：把 `WEB3_BACKFILL_MAX_BLOCKS` 设为正数（如 10000）限幅，单次只推进到上限并在响应里返回 `limited=true`、`cursorAfter=…`，
下次用同一 `fromBlock` 重新调用会从游标自动接着跑，无需人工记位。

**Q：充值会重复入账吗？下游会不会收到两条同样消息？**
A：不会。`deposit_record` 上 `(chain_id, transaction_hash, log_index)` 唯一键 + `INSERT IGNORE`，只有真正新增的行才发 `deposit_channel`，
重连重放、补拉分页重叠、实时与补拉双写都只通知一次；消息体带 `depositNo`，下游可按此再做一层幂等。验证：重跑同一补拉区间，
日志里 `matched>0` 而 `inserted=0`、`published=0`，`SELECT COUNT(*) FROM deposit_record` 不变。

**Q：Redis 挂了会不会丢充值？**
A：不会丢入账，只会暂时收不到通知。先落库再发布，发布失败只把 `notify_status` 置 `FAILED`（充值行已提交），
后续按 `notify_status='FAILED'` 补偿重发即可；而路由自身报错不会拖垮实时订阅与补拉（流水已入库，重跑区间可补齐）。

**Q：热钱包地址加了却不生成充值记录？**
A：先看路由索引里有没有它：`curl http://localhost:8080/api/chain/deposit/wallets`（或先调 `POST /deposit/wallets/refresh`）。
常见原因是地址写了大写、`status=0`，或配了 `contract` 但事件来自另一个代币（串账隔离是有意行为）。
配置中心 `web3.deposit.hot-wallets` 与 DB 同地址时以 DB 为准，库里停用不会被配置文件“兜底复活”。

**Q：为什么全部充值都是 `PENDING_CONFIRM`？**
A：本期只负责“发现即入账”，确认推进（`CREDITED`）属于确认任务的范围，`confirm-threshold` 已随消息下发供下游自行判断；
链重组则置 `INVALID`（需额外的回退机制，见“规划中”）。

**Q：为什么有的接口接受 `0X...` 地址、有的却报参数非法？**
A：设两口径（见 [5.12](#512-重复口径收拢patternconsts--chainparams--chainqueryutils)）。日志/补拉类接口的合约地址会原样拼进 `eth_getLogs` 发给节点，
所以只收严格 `^0x`；充值地址配置（`POST /api/chain/deposit/wallets` 与 `web3.deposit.hot-wallets`）会先归一小写再入库/匹配，
因此 `0X` 前缀也接受。大写十六进制字母（EIP-55 校验和地址）两边都能识别。

**Q：配置好 Infura URL 后接口/扫描超时，但浏览器/curl 能访问？**
A：本机开了系统代理（如 Clash `127.0.0.1:7890`），OkHttp 不读系统代理。设置 `WEB3_PROXY_HOST` / `WEB3_PROXY_PORT` 让 web3j 经代理访问。生产海外服务器通常直连即可。

**Q：Redisson 启动报 AUTH 错误？**
A：本地 Redis 无密码时，`application.yml` 不要写 `password` 行（即便空串），否则 Redisson 仍会发送 AUTH 导致连接失败。

**Q：`chain-id` 和扫描的链不一致？**
A：`web3.infura.chain-id` 必须与 `http-url`、`web3.websocket.url` 指向同一网络（主网 `1`，Sepolia `11155111`）。

**Q：区块扫描一直没跑？**
A：`BlockScanner` 的 `@Scheduled` 当前是注释状态，而 `xxl.job.enabled` 默认 `false`，两条触发通道都没开。本地调试请临时打开 `@Scheduled`，或部署 XXL-Job 调度中心并置 `XXL_JOB_ENABLED=true`。
