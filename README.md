# web3-backend

> 基于 **Web3j + Infura** 的以太坊后端骨架，面向钱包 / 充值提币 / 链上数据查询场景。
> 当前提供链上状态查询、最新区块高度、地址余额三个 REST 接口，并内置多实例安全的区块扫描器。

- JDK 21 + Spring Boot 3.5 + MySQL/Redis + MyBatis-Plus + Web3j + Infura
- 技术选型详解见 [`docs/技术选型.md`](docs/技术选型.md)

---

## 一、功能特性

### 已实现
- **链上查询 REST API**：客户端版本 / 链 ID / 最新区块高度 / Gas Price / 地址余额（wei + ether）
- **区块扫描器**：定时轮询最新区块高度并把进度落库到 `block_sync_state`
  - 双触发通道：Spring Scheduler（默认）+ XXL-Job（分布式部署可选）
  - Redisson 分布式锁：多实例部署时仅一个节点执行
- **RPC 容错**：Resilience4j 对 Infura 调用做重试 + 熔断
- **统一响应与异常处理**：`ApiResponse` 封装 + `GlobalExceptionHandler`
- **API 文档**：SpringDoc Swagger UI
- **可观测性**：Actuator + Micrometer Prometheus 指标
- **代理可配置**：解决本机/受限网络需经代理访问 Infura 的问题（详见 [4.4](#44-http-rpc-代理本地受限网络))

### 规划中
- ERC20 合约交互、充值事件监听（WebSocket）、提币服务、多链扩展、托管钱包

---

## 二、技术栈

| 层面 | 选型 | 版本 | 职责 |
|---|---|---|---|
| 语言/运行时 | JDK | 21 (LTS) | 虚拟线程收益、新版 GC |
| 应用框架 | Spring Boot | 3.5.5 | Web 容器、DI、配置管理 |
| 链交互库 | Web3j | 4.12.3 | JSON-RPC 调用、ABI、事件订阅 |
| 链节点接入 | Infura | — | 托管 RPC 节点（HTTP + WebSocket） |
| 构建工具 | Maven | 3.9.x | 依赖管理与打包 |
| ORM | MyBatis-Plus | 3.5.9 | 扫描进度等持久化 |
| 数据库 | MySQL | 8.x | `block_sync_state`、流水表 |
| 缓存 | Redis | 7.x | 热点缓存、分布式协调 |
| 分布式锁 | Redisson | 3.41.0 | 区块扫描互斥、提币防重 |
| RPC 容错 | Resilience4j | 2.2.0 | 重试 + 熔断 |
| 任务调度 | Spring Scheduler + XXL-Job | 2.4.2（可选） | 扫块触发 |
| API 文档 | SpringDoc OpenAPI | 2.8.5 | Swagger UI |
| 监控 | Actuator + Micrometer + Prometheus | Boot BOM | 指标暴露 |
| 编码简化 | Lombok | Boot BOM | `@Data`/`@Slf4j`/`@RequiredArgsConstructor` |

---

## 三、架构与目录结构

### 3.1 分层架构

```
┌─────────────────────────────────────────────────────┐
│  Controller 层  /api/chain/**（SpringDoc 注解）       │
├─────────────────────────────────────────────────────┤
│  Service 层     ChainService（Resilience4j 重试/熔断）│
├──────────────┬──────────────────────────────────────┤
│ Web3j Client │ Scanner（Scheduler/XXL-Job + Redisson）│
│  (Infura RPC)│  区块轮询 → 扫描进度落库               │
├──────────────┴──────────────────────────────────────┤
│  持久层  MyBatis-Plus → MySQL    缓存/锁 → Redis      │
├─────────────────────────────────────────────────────┤
│  可观测  Actuator + Prometheus  文档  Swagger UI      │
└─────────────────────────────────────────────────────┘
```

### 3.2 目录结构

```
web3-backend
├── pom.xml
├── sql/schema.sql                          # MySQL 初始化脚本
├── docs/技术选型.md                          # 技术选型详解
└── src/main/java/com/ray/web3
    ├── Web3BackendApplication.java         # 启动类（@EnableScheduling + @MapperScan）
    ├── config/
    │   ├── InfuraProperties.java           # web3.infura.* 配置绑定（含代理项）
    │   ├── Web3jConfig.java                # Web3j Bean（HTTP RPC + 可选代理）
    │   ├── RedisConfig.java                # RedisTemplate JSON 序列化
    │   └── XxlJobConfig.java               # XXL-Job 执行器（默认关闭）
    ├── controller/ChainController.java     # 链上查询 REST API
    ├── service/
    │   ├── ChainService.java               # 链上查询接口
    │   └── impl/ChainServiceImpl.java       # Web3j 调用 + 容错注解
    ├── scanner/BlockScanner.java           # 区块扫描器（双触发 + 分布式锁）
    ├── mapper/BlockSyncStateMapper.java    # MyBatis-Plus Mapper
    ├── model/
    │   ├── entity/BlockSyncState.java      # 扫描进度实体
    │   └── vo/                              # ApiResponse / ChainStatusVO / BalanceVO（record）
    └── exception/GlobalExceptionHandler.java
```

---

## 四、REST API

基础路径：`/api/chain`，统一响应结构 `ApiResponse<T>{ code, message, data }`（`code=0` 表示成功）。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/chain/status` | 链上状态：客户端版本 / 链 ID / 最新区块高度 / Gas Price |
| GET | `/api/chain/block-number` | 最新区块高度 |
| GET | `/api/chain/balance/{address}` | 地址余额，返回 wei 与 ether 两种单位 |

**示例**

```bash
# 链上状态
curl http://localhost:8080/api/chain/status

# 最新区块高度
curl http://localhost:8080/api/chain/block-number

# 地址余额
curl http://localhost:8080/api/chain/balance/0x你的地址
```

**响应示例**

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "clientVersion": "Geth/v1.x.x",
    "chainId": 1,
    "latestBlock": 21000000,
    "gasPrice": 15000000000
  }
}
```

在线文档：`http://localhost:8080/swagger-ui.html`

---

## 五、核心模块说明

### 5.1 链上交互（Web3j + Infura）
- `Web3jConfig` 基于 `HttpService(infura-http-url)` 构建单例 `Web3j`，`destroyMethod = "shutdown"` 保证退出时释放 OkHttp 连接池。
- Infura Project ID 通过环境变量 `INFURA_PROJECT_ID` 注入，**不写入代码库**。
- 默认对接主网（`chainId=1`）；测试网改 `web3.infura.*` 的 URL 与 `chain-id` 即可。

### 5.2 RPC 容错（Resilience4j）
- `ChainServiceImpl` 所有方法标注 `@Retry(name = "rpc")` + `@CircuitBreaker(name = "rpc")`。
- 策略：最多重试 3 次（间隔 500ms）；滑动窗口 20，失败率 ≥ 50% 熔断 30s。
- 兜底由 `GlobalExceptionHandler` 将 RPC 异常统一转为 `502`。

### 5.3 区块扫描（Scheduler + XXL-Job + Redisson）
- `BlockScanner` 每 `web3.scanner.interval-ms`（默认 30s）轮询最新区块高度，进度写入 `block_sync_state`。
- **双通道**：默认 Spring Scheduler 触发（`web3.scanner.enabled=true`）；`XXL_JOB_ENABLED=true` 时由调度中心触发 `blockScanHandler`。
- **Redisson `tryLock`** 保证多实例仅一个节点执行，未抢到锁直接跳过本轮。

### 5.4 HTTP RPC 代理（本地/受限网络）
Infura RPC 地址通过 `HttpService` 发出。OkHttp **不读取 Windows/系统代理**，因此在需要经代理才能访问外网的环境（如本机开 Clash、或国内云服务器）会出现「curl 能连、Java 超时」的现象。`Web3jConfig` 支持可选代理：

```yaml
web3:
  infura:
    proxy-host: ${WEB3_PROXY_HOST:}   # 留空=直连；本例填 127.0.0.1
    proxy-port: ${WEB3_PROXY_PORT:}   # 本例填 7890
```

- 配了 `proxy-host` + `proxy-port` → web3j 经该 HTTP 代理访问 RPC；
- 留空 → 直连（生产海外云服务器通常无需代理）。
- **建议用环境变量注入代理，避免把本地代理地址（如 `127.0.0.1:7890`）写死到生产配置。**

### 5.5 数据模型
`BlockSyncState`（表 `block_sync_state`）记录扫描进度：
- `chain_id`：扫描的链 ID；
- `contract_address`：合约地址，空串表示原生链；
- `latest_scanned_block`：已扫描区块高度；
- `(chain_id, contract_address)` 唯一键。

### 5.6 安全与密钥
- 私钥严禁硬编码：本地用环境变量/`.env`（勿提交），生产建议 Vault 或托管钱包（Fireblocks/Turnkey）。
- keystore 文件已在 `.gitignore` 排除（`wallet/`、`*.keystore`）。

---

## 六、快速开始

### 6.1 环境要求
| 组件 | 要求 |
|---|---|
| JDK | 21（`JAVA_HOME` 需指向 JDK 21，IDEA 中 Project SDK 同步切换） |
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

# 如需经本地代理访问 RPC（本机开了 Clash/v2ray 时）
$env:WEB3_PROXY_HOST = "127.0.0.1"
$env:WEB3_PROXY_PORT = "7890"

# 打包
mvn clean package -DskipTests

# 启动
java -jar target/web3-backend-1.0.0-SNAPSHOT.jar
```

### 6.4 验证入口
| 入口 | 地址 |
|---|---|
| 链上状态 | `GET http://localhost:8080/api/chain/status` |
| 最新区块 | `GET http://localhost:8080/api/chain/block-number` |
| 地址余额 | `GET http://localhost:8080/api/chain/balance/{address}` |
| Swagger UI | `http://localhost:8080/swagger-ui.html` |
| 健康检查 | `http://localhost:8080/actuator/health` |
| Prometheus 指标 | `http://localhost:8080/actuator/prometheus` |

---

## 七、配置项一览（`application.yml`）

| 配置 | 环境变量 | 默认值 | 说明 |
|---|---|---|---|
| `web3.infura.project-id` | `INFURA_PROJECT_ID` | 空 | Infura Project ID |
| `web3.infura.http-url` | — | 主网 URL | HTTP RPC 地址 |
| `web3.infura.ws-url` | — | 主网 WS 地址 | WebSocket RPC 地址 |
| `web3.infura.chain-id` | — | 1 | 链 ID（主网=1，Sepolia=11155111） |
| `web3.infura.proxy-host` | `WEB3_PROXY_HOST` | 空 | HTTP RPC 代理主机，留空直连 |
| `web3.infura.proxy-port` | `WEB3_PROXY_PORT` | 空 | HTTP RPC 代理端口 |
| `web3.scanner.enabled` | — | true | 是否启用区块扫描 |
| `web3.scanner.interval-ms` | — | 30000 | 扫描间隔（毫秒） |
| `xxl.job.enabled` | `XXL_JOB_ENABLED` | false | 是否启用 XXL-Job 执行器 |
| `xxl.job.admin.addresses` | `XXL_JOB_ADMIN_ADDRESSES` | `http://localhost:8088/xxl-job-admin` | 调度中心地址 |
| `xxl.job.access-token` | `XXL_JOB_ACCESS_TOKEN` | 空 | 调度中心通信令牌 |

---

## 八、常见问题

**Q：配置好 Infura URL 后接口/扫描超时，但浏览器/curl 能访问？**
A：本机开启了系统代理（如 Clash `127.0.0.1:7890`），而 Java 默认不走系统代理。设置 `WEB3_PROXY_HOST` / `WEB3_PROXY_PORT` 让 web3j 经代理访问，或启动加 `-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890`。生产海外服务器通常直连即可，无需配置。

**Q：Redisson 启动报 AUTH 错误？**
A：本地 Redis 无密码时，`application.yml` 不要写 `password` 行（即便空串），否则 Redisson 仍会发送 AUTH 导致连接失败。

**Q：`chain-id` 和扫描的链不一致？**
A：`web3.infura.chain-id` 必须与 `http-url` 指向的网络一致（主网 URL 配 `1`，Sepolia 配 `11155111`）。
