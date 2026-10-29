# web3-backend

> 基于 **Web3j 6 + Infura** 的以太坊后端骨架，面向钱包 / 充值提币 / 链上数据查询场景。
> 已提供 4 个链上查询 REST 接口、区块扫描进度落库、以及 WebSocket（`eth_subscribe`）实时区块/转账监听。

- JDK 21 + Spring Boot 4.1.2 + Spring 7.0.10 + MySQL/Redis + MyBatis-Plus + Web3j 6.1.0 + Infura
- 技术选型详解见 [`docs/技术选型.md`](docs/技术选型.md)

---

## 一、功能特性

### 已实现
- **链上查询 REST API**：客户端版本 / 链 ID / 最新区块高度 / Gas Price / 地址余额（wei + ether）/ 分页拉取合约日志
- **WebSocket 实时订阅**（`web3.websocket.enabled=true` 时生效）
  - `newHeads`：新区块头推送（高度、hash、gasUsed、时间）
  - `logs`：按合约 + `Transfer` topic 过滤的转账事件推送
  - 两者都走 `eth_subscribe`，回调在 WebSocket 读线程执行，`CompositeDisposable` 统一退订
- **区块扫描器**：轮询最新区块高度并把进度落库到 `block_sync_state`，Redisson 分布式锁保证多实例仅一个节点执行
- **RPC 容错**：Resilience4j 两套实例——查询接口 `rpc`，分页拉日志 `rpc-segment`（按单段粒度重试）
- **代理可配置**：HTTP RPC 经 OkHttp 代理；wss 经自建 HTTP CONNECT 隧道（详见 [5.5](#55-代理穿透http-rpc--wss)）
- **统一响应与异常处理**：`ApiResponse` 封装 + `GlobalExceptionHandler`（RPC 类异常统一转 `502`）
- **API 文档**：SpringDoc OpenAPI 3.1（`/swagger-ui.html`、`/v3/api-docs`）
- **可观测性**：Actuator + Micrometer Prometheus

### 规划中
- 充值事件落库（当前 WS 监听只打日志，落库仍以扫描器为准）、提币服务、多链扩展、托管钱包

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
├───────────┬──────────────────┬───────────────────────────┤
│ web3j     │ wsWeb3j          │ Scanner（Scheduler/XXL-Job  │
│ (HTTP RPC │ (WebSocket 长连接 │  + Redisson 锁)            │
│  OkHttp)  │  newHeads/logs)  │  区块轮询 → 进度落库         │
├───────────┴──────────────────┴───────────────────────────┤
│  持久层  MyBatis-Plus → MySQL     缓存/锁 → Redis          │
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
├── sql/schema.sql                            # MySQL 初始化脚本
├── docs/技术选型.md                            # 技术选型详解
└── src/main/java/com/ray/web3
    ├── Web3BackendApplication.java           # 启动类（@EnableScheduling + @MapperScan）
    ├── config/
    │   ├── InfuraProperties.java             # web3.infura.* 配置绑定（含代理项、log-chunk-size）
    │   ├── WebSocketProperties.java          # web3.websocket.* 配置绑定（url/代理/订阅开关/合约列表）
    │   ├── Web3jConfig.java                  # HTTP Web3j Bean（OkHttp 可选代理）
    │   ├── WebSocketConfig.java              # WebSocketService + wsWeb3j Bean（CONNECT 隧道注入）
    │   ├── RedisConfig.java                  # RedisTemplate 序列化（Jackson 3）
    │   └── XxlJobConfig.java                 # XXL-Job 执行器（xxl.job.enabled=true 才加载）
    ├── consts/EventConsts.java               # ERC-20 Transfer 事件定义（ABI 编码用）
    ├── controller/ChainController.java       # 链上查询 REST API
    ├── listener/ChainEventListener.java      # WS 实时订阅（newHeads + logs）
    ├── service/
    │   ├── ChainService.java                 # 链上查询接口
    │   ├── EventLogService.java              # eth_getLogs 分页拉取接口
    │   ├── RpcCallService.java               # 单段 RPC 调用（承载重试/熔断注解）
    │   └── impl/
    │       ├── ChainServiceImpl.java         # Web3j 调用 + @Retry/@CircuitBreaker("rpc")
    │       ├── EventLogServiceImpl.java      # 分段循环 + Log → TransferLogVO 解析
    │       └── RpcCallServiceImpl.java       # @Retry/@CircuitBreaker("rpc-segment")
    ├── scanner/BlockScanner.java             # 区块扫描器（双触发 + 分布式锁）
    ├── mapper/BlockSyncStateMapper.java      # MyBatis-Plus Mapper
    ├── model/
    │   ├── entity/BlockSyncState.java        # 扫描进度实体
    │   └── vo/                               # ApiResponse / ChainStatusVO / BalanceVO / TransferLogVO（record）
    ├── util/ProxyTunnel.java                 # HTTP CONNECT 隧道 + TLS 升级（wss 代理穿透）
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
| GET | `/api/chain/logs/transfer/{contract}` | 分段拉取指定合约区间内的链上日志，`fromBlock` / `toBlock` 为必填 query 参数 |

**示例**

```bash
curl http://localhost:8080/api/chain/block-number

curl http://localhost:8080/api/chain/balance/0x你的地址

# 拉取 USDT 合约两个区块之间的日志（块跨度受 web3.infura.log-chunk-size 分段）
curl "http://localhost:8080/api/chain/logs/transfer/0xdac17f958d2ee523a2206206994597c13d831ec7?fromBlock=26297100&toBlock=26297101"
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
  `RpcCallServiceImpl.ethGetLogs` 标注 `@Retry/@CircuitBreaker(name = "rpc-segment")`，使分页拉取**按单段粒度重试**，不重跑整个拉取过程。
- 策略：最多重试 3 次（间隔 500ms）；滑动窗口 20、最小调用数 5、失败率 ≥ 50% 熔断 30s。
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

### 5.6 eth_getLogs 分页拉取（EventLogServiceImpl）
- 校验合约地址格式与区块区间 → 用 `ethBlockNumber` 截断超出链上高度的 `toBlock` → 按 `web3.infura.log-chunk-size` 分段循环调用 `eth_getLogs`。
- 结果解析：`topic[1]` = from、`topic[2]` = to（各取 32 字节 topic 末 20 字节）、`data` = value。
- **当前行为提示**：`buildFilter` 里 `EventEncoder.encode(TRANSFER_EVENT)` 一行被注释，改用 `addNullTopic()`，
  所以返回的是该合约区间内的**全部事件日志**而非仅 Transfer；同时"命中节点结果数上限时按节点建议跨度自动缩小重试"的自适应降级逻辑也处于注释状态，
  撞到 Infura `query returned more than 10000 results` 会直接抛错（响应 `502`），需手工调小 `WEB3_LOG_CHUNK_SIZE`。

### 5.7 Redis 与序列化
- `RedisConfig` 为 `RedisTemplate<String, Object>` 配置 key=String、value=JSON。
  Boot 4（Spring Data Redis 4）下基于 Jackson 2 的 `GenericJackson2JsonRedisSerializer` 已 `@Deprecated(forRemoval = true)`，现改用 Jackson 3 的 **`GenericJacksonJsonRedisSerializer`**。
- mapper 用 `JsonMapper.builder()` 构建（Jackson 3 的 `ObjectMapper` 不可变）；`WRITE_DATES_AS_TIMESTAMPS` 已从 `SerializationFeature` 移到 `DateTimeFeature`，且 java.time 支持内置于 databind，**不再需要注册 `JavaTimeModule`**。
- 单参构造不启用 default typing（与 Jackson 2 版行为一致），即不写 `@class`，`deserialize` 返回 `LinkedHashMap`；需要具体类型回读时改用 `builder().enableDefaultTyping(ptv)`。
- 该 Bean 目前仅供后续缓存业务使用，尚未被业务代码调用。Redisson 独立使用自己的编解码。

### 5.8 数据模型
`BlockSyncState`（表 `block_sync_state`）记录扫描进度：

| 列 | 说明 |
|---|---|
| `chain_id` | 扫描的链 ID |
| `contract_address` | 合约地址，空串表示原生链 |
| `latest_scanned_block` | 已扫描区块高度 |
| `updated_at` | DB 层 `ON UPDATE CURRENT_TIMESTAMP` |
| `uk_chain_contract` | `(chain_id, contract_address)` 唯一键 |

### 5.9 安全与密钥
- 私钥严禁硬编码：本地用环境变量/`.env`（勿提交），生产建议 Vault 或托管钱包（Fireblocks/Turnkey）。
- keystore 文件已在 `.gitignore` 排除（`wallet/`、`*.keystore`）。

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

### 6.3 编译打包（PowerShell）
```powershell
# 注入 Infura 项目 ID
$env:INFURA_PROJECT_ID = "你的projectId"

# 如需经本地代理访问 Infura（HTTP RPC 与 wss 共用这两个变量；本机开 Clash 时需要）
$env:WEB3_PROXY_HOST = "127.0.0.1"
$env:WEB3_PROXY_PORT = "7890"

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
| Swagger UI | `http://localhost:8080/swagger-ui.html` |
| OpenAPI 描述 | `http://localhost:8080/v3/api-docs` |
| 健康检查 | `http://localhost:8080/actuator/health` |
| Prometheus 指标 | `http://localhost:8080/actuator/prometheus` |

启动成功的标志：日志出现 `Tomcat started on port 8080`、（启用 WS 时）`WebSocket 连接成功` 与
`已订阅 newHeads` / `已订阅 Transfer 日志（eth_subscribe logs）`，之后持续输出 `[newHeads]`、`[logs]` 推送。

---

## 七、配置项一览（`application.yml`）

| 配置 | 环境变量 | 默认值 | 说明 |
|---|---|---|---|
| `spring.datasource.url` | `MYSQL_HOST` / `MYSQL_PORT` / `MYSQL_DATABASE` | `localhost:3306/web3_backend` | MySQL 连接 |
| `spring.datasource.username/password` | `MYSQL_USERNAME` / `MYSQL_PASSWORD` | `root` / `root123` | 账号密码 |
| `spring.data.redis.host/port/database` | `REDIS_HOST` / `REDIS_PORT` / `REDIS_DATABASE` | `localhost:6379:0` | Redis 连接 |
| `mybatis-plus.*` | — | 下划线转驼峰、`id-type: auto` | ORM 行为 |
| `web3.infura.project-id` | `INFURA_PROJECT_ID` | 空 | Infura Project ID |
| `web3.infura.http-url` | — | 主网 URL | HTTP RPC 地址 |
| `web3.infura.chain-id` | — | 1 | 链 ID（主网=1，Sepolia=11155111） |
| `web3.infura.proxy-host` | `WEB3_PROXY_HOST` | `127.0.0.1` | HTTP RPC 代理主机，留空直连 |
| `web3.infura.proxy-port` | `WEB3_PROXY_PORT` | `7890` | HTTP RPC 代理端口 |
| `web3.infura.log-chunk-size` | `WEB3_LOG_CHUNK_SIZE` | 100 | `eth_getLogs` 单段最大区块跨度（节点限制严时调小到 50/10） |
| `web3.scanner.enabled` | — | true | 是否加载区块扫描器 Bean |
| `web3.scanner.interval-ms` | — | 30000 | 扫描间隔（`@Scheduled` 当前被注释，改动生效需打开注解） |
| `web3.websocket.enabled` | `WEB3_WS_ENABLED` | true | 是否建立 WS 连接与订阅 |
| `web3.websocket.url` | — | `wss://mainnet.infura.io/ws/v3/{projectId}` | WS 端点（本地节点用 `ws://127.0.0.1:8545`） |
| `web3.websocket.proxy-host` | `WEB3_PROXY_HOST` | `127.0.0.1` | wss 的 CONNECT 隧道代理主机，留空直连 |
| `web3.websocket.proxy-port` | `WEB3_PROXY_PORT` | `7890` | wss 的 CONNECT 隧道代理端口 |
| `web3.websocket.subscribe-new-heads` | — | true | 是否订阅 `newHeads` |
| `web3.websocket.watch-contracts` | `WEB3_WATCH_CONTRACTS` | USDT 合约 | 监听 Transfer 的合约列表，逗号分隔；留空=全部合约 |
| `resilience4j.retry/circuitbreaker.instances.rpc` | — | 3 次 / 500ms，窗口 20、阈值 50%、开路 30s | 链上查询容错 |
| `resilience4j.*.instances.rpc-segment` | — | 同上 | 单段 `eth_getLogs` 容错 |
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
A：Infura 单次结果数上限被打穿。当前自适应缩小跨度的逻辑未启用，需调小 `WEB3_LOG_CHUNK_SIZE`（如 10~50）重试。

**Q：配置好 Infura URL 后接口/扫描超时，但浏览器/curl 能访问？**
A：本机开了系统代理（如 Clash `127.0.0.1:7890`），OkHttp 不读系统代理。设置 `WEB3_PROXY_HOST` / `WEB3_PROXY_PORT` 让 web3j 经代理访问。生产海外服务器通常直连即可。

**Q：Redisson 启动报 AUTH 错误？**
A：本地 Redis 无密码时，`application.yml` 不要写 `password` 行（即便空串），否则 Redisson 仍会发送 AUTH 导致连接失败。

**Q：`chain-id` 和扫描的链不一致？**
A：`web3.infura.chain-id` 必须与 `http-url`、`web3.websocket.url` 指向同一网络（主网 `1`，Sepolia `11155111`）。

**Q：区块扫描一直没跑？**
A：`BlockScanner` 的 `@Scheduled` 当前是注释状态，而 `xxl.job.enabled` 默认 `false`，两条触发通道都没开。本地调试请临时打开 `@Scheduled`，或部署 XXL-Job 调度中心并置 `XXL_JOB_ENABLED=true`。
