-- web3-backend 初始化脚本
CREATE DATABASE IF NOT EXISTS web3_backend DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE web3_backend;

-- 区块扫描进度表（latest_scanned_block 同时充当历史补拉的 lastProcessedBlock 游标：
-- contract_address 为合约地址时，该列值 = 该合约已完整处理到的区块高度，下一轮从 +1 开始）
CREATE TABLE IF NOT EXISTS block_sync_state
(
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    chain_id              BIGINT      NOT NULL COMMENT '链ID，Sepolia=11155111',
    contract_address      VARCHAR(64) NOT NULL DEFAULT '' COMMENT '合约地址，空串表示原生链',
    latest_scanned_block  BIGINT      NOT NULL DEFAULT 0 COMMENT '已处理区块高度（历史补拉游标 lastProcessedBlock）',
    updated_at            DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_chain_contract (chain_id, contract_address)
) ENGINE = InnoDB COMMENT ='区块扫描进度/历史补拉游标';

-- ERC-20 Transfer 事件流水表：历史补拉每一页「事件落库 + 游标推进」同事务，保证不漏单
-- 金额列用变长字符串而非 DECIMAL：MySQL DECIMAL 最大精度 65 位，容不下 uint256（最多 78 位），
-- 监听任意合约时若碰到超大规模金额会整页回滚、游标永久卡住；字符串写入不会失败
CREATE TABLE IF NOT EXISTS transfer_event_log
(
    id               BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    chain_id         BIGINT      NOT NULL COMMENT '链ID，Sepolia=11155111',
    contract_address VARCHAR(64) NOT NULL COMMENT '代币合约地址（小写）',
    block_number     BIGINT      NOT NULL COMMENT '区块高度',
    transaction_hash VARCHAR(80) NOT NULL COMMENT '交易哈希',
    log_index        BIGINT      NOT NULL COMMENT '日志在区块内的序号',
    from_address     VARCHAR(64) NOT NULL COMMENT '转出地址',
    to_address       VARCHAR(64) NOT NULL COMMENT '转入地址',
    raw_value        VARCHAR(78) NOT NULL COMMENT '原始金额（最小单位整数字符串，容纳 uint256 全量）',
    display_value    VARCHAR(80) NOT NULL COMMENT '按代币精度换算后的金额（十进制字符串，保留尾零）',
    decimals         INT         NOT NULL COMMENT '换算所用代币精度（ETH/WRAP 18、USDT/USDC 6）',
    updated_at       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    created_at       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '入库时间',
    UNIQUE KEY uk_chain_tx_log (chain_id, transaction_hash, log_index),
    KEY idx_contract_block (contract_address, block_number)
) ENGINE = InnoDB COMMENT ='ERC-20 Transfer 事件流水';

-- 充值地址表（REQ-BIZ-04 热钱包匹配）：平台热钱包 + 用户充值地址统一维护，实时链路与补拉链路共用
-- 匹配按 to_address 小写等值命中，故唯一键直接落在 (chain_id, address)：同链同地址只允许一条有效配置
CREATE TABLE IF NOT EXISTS deposit_wallet
(
    id            BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    chain_id      BIGINT      NOT NULL COMMENT '链ID，Sepolia=11155111',
    address       VARCHAR(64) NOT NULL COMMENT '充值地址（小写）',
    wallet_type   VARCHAR(16) NOT NULL DEFAULT 'HOT' COMMENT '地址类型：HOT=平台热钱包，USER=用户充值地址',
    user_id       VARCHAR(64)          DEFAULT NULL COMMENT '用户标识，wallet_type=USER 时必填',
    currency      VARCHAR(32)          DEFAULT NULL COMMENT '归属币种符号（如 USDT），仅作展示/对账',
    contract      VARCHAR(64)          DEFAULT NULL COMMENT '限定代币合约（小写）；NULL=任意合约到账均视为该地址充值',
    status        TINYINT     NOT NULL DEFAULT 1 COMMENT '1=启用，0=停用（停用后不再路由新充值）',
    remark        VARCHAR(255)         DEFAULT NULL COMMENT '备注',
    updated_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    created_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_chain_address (chain_id, address),
    KEY idx_chain_type_status (chain_id, wallet_type, status)
) ENGINE = InnoDB COMMENT ='平台热钱包/用户充值地址配置';

-- 充值记录表（REQ-BIZ-04 入账 + 幂等）：唯一键 (chain_id, transaction_hash, log_index) 是幂等的最终防线，
-- 重连重放、补拉区间重叠、实时与补拉双写都靠 INSERT IGNORE 消化，不会产生重复入账
-- 金额列同 transfer_event_log 用字符串：uint256 最多 78 位，MySQL DECIMAL 最大 65 位容不下
CREATE TABLE IF NOT EXISTS deposit_record
(
    id               BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    deposit_no       VARCHAR(64) NOT NULL COMMENT '充值单号（对外业务主键）',
    chain_id         BIGINT      NOT NULL COMMENT '链ID，Sepolia=11155111',
    contract_address VARCHAR(64) NOT NULL COMMENT '代币合约地址（小写）',
    transaction_hash VARCHAR(80) NOT NULL COMMENT '交易哈希（幂等键之一）',
    log_index        BIGINT      NOT NULL COMMENT '日志在区块内的序号（幂等键之一）',
    block_number     BIGINT      NOT NULL COMMENT '入账区块高度，确认数由此与链上最新高度差值算出',
    from_address     VARCHAR(64) NOT NULL COMMENT '转出地址（用户充值来源）',
    to_address       VARCHAR(64) NOT NULL COMMENT '转入地址（命中的平台热钱包/用户充值地址）',
    wallet_type      VARCHAR(16) NOT NULL COMMENT '命中地址类型：HOT/USER',
    user_id          VARCHAR(64)          DEFAULT NULL COMMENT '归属用户，wallet_type=USER 时非空',
    currency         VARCHAR(32)          DEFAULT NULL COMMENT '币种符号',
    raw_value        VARCHAR(78) NOT NULL COMMENT '原始金额（最小单位整数字符串，容纳 uint256 全量）',
    display_value    VARCHAR(80) NOT NULL COMMENT '按代币精度换算后的到账金额（十进制字符串）',
    decimals         INT         NOT NULL COMMENT '换算所用代币精度',
    status           VARCHAR(16) NOT NULL DEFAULT 'PENDING_CONFIRM' COMMENT '充值状态：PENDING_CONFIRM=待确认，CREDITED=已入账，INVALID=已作废（链重组）',
    confirmed_block  BIGINT               DEFAULT NULL COMMENT '达到确认阈值时的区块高度',
    event_source     VARCHAR(16) NOT NULL COMMENT '事件来源：STREAM=WebSocket 实时订阅，BACKFILL=历史补拉',
    notify_status    VARCHAR(16) NOT NULL DEFAULT 'INIT' COMMENT '下游通知状态：INIT/SENT/FAILED（发布失败可据此补偿重发）',
    notify_at        DATETIME             DEFAULT NULL COMMENT '最近一次成功发布时间',
    updated_at       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    created_at       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '入库时间',
    UNIQUE KEY uk_chain_tx_log (chain_id, transaction_hash, log_index),
    UNIQUE KEY uk_deposit_no (deposit_no),
    KEY idx_status_block (status, block_number),
    KEY idx_to_address (chain_id, to_address)
) ENGINE = InnoDB COMMENT ='充值记录（热钱包匹配入账，状态待确认）';

-- 钱包 Nonce 分配表（第 3 周：写与发交易）：热钱包对外发交易的 Nonce 由本表单点分配，
-- 杜绝“读链上 Nonce → 签名 → 广播”窗口期内并发抢重（同 Nonce 后发覆盖先发 = 丢单）。
-- 分配走 SELECT ... FOR UPDATE 行锁串行化；链上校准规则：next_nonce = max(本表值, 链上 pending)，
-- 只前进不回退（回退会把已上链 Nonce 再用作 = 双花风险）
CREATE TABLE IF NOT EXISTS wallet_nonce
(
    id         BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    chain_id   BIGINT      NOT NULL COMMENT '链ID，Sepolia=11155111',
    address    VARCHAR(64) NOT NULL COMMENT '钱包地址（小写），即交易发送方',
    next_nonce BIGINT      NOT NULL COMMENT '下一个待分配的 Nonce（分配即推进）',
    updated_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_chain_address (chain_id, address)
) ENGINE = InnoDB COMMENT ='发交易钱包的 Nonce 分配游标';

-- 提币记录表（第 3 周初步）：先落库后广播（INIT→BROADCAST），广播超时结果不确定时
-- 依据本行记录的 nonce 上链查询判定，而非盲目重试（防双发）。金额列同充值侧口径用字符串：
-- uint256 最多 78 位，MySQL DECIMAL 最大 65 位容不下
CREATE TABLE IF NOT EXISTS withdraw_record
(
    id              BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    withdraw_no     VARCHAR(64)  NOT NULL COMMENT '提币单号（对外业务主键）',
    chain_id        BIGINT       NOT NULL COMMENT '链ID，Sepolia=11155111',
    contract_address VARCHAR(64) NOT NULL COMMENT '代币合约地址（小写）',
    from_address    VARCHAR(64)  NOT NULL COMMENT '发起方热钱包地址',
    to_address      VARCHAR(64)  NOT NULL COMMENT '提币目标地址',
    raw_value       VARCHAR(78)  NOT NULL COMMENT '提币金额（最小单位整数字符串，容纳 uint256 全量）',
    nonce           BIGINT       NOT NULL COMMENT '本笔交易占用的 Nonce（卡住替换/结果不确定判定的锚点）',
    gas_limit       BIGINT       NOT NULL COMMENT '估算并加安全垫后的 gas 上限',
    gas_price       BIGINT       NOT NULL COMMENT '广播时使用的 gas 单价（wei），加速后为最新一笔的提价值',
    accelerate_count INT         NOT NULL DEFAULT 0 COMMENT '已提价重播次数（Day 5-7 RBF 加速计数，达上限即停止自动加速转人工）',
    transaction_hash VARCHAR(80)         DEFAULT NULL COMMENT '当前生效的交易哈希（加速重播后被同 Nonce 的新交易哈希原地替换）',
    status          VARCHAR(24)  NOT NULL DEFAULT 'INIT' COMMENT '状态：INIT=已落库未广播，BROADCAST=已广播待确认，SUCCESS/FAILED=Day3-4 追踪回填，REPLACED=被加速/撤销交易覆盖作废',
    error_message   VARCHAR(512)          DEFAULT NULL COMMENT '失败/拒绝原因（估算 revert、节点拒接、卡单标注等）',
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_withdraw_no (withdraw_no),
    UNIQUE KEY uk_chain_wallet_nonce (chain_id, from_address, nonce),
    KEY idx_status (status, updated_at)
) ENGINE = InnoDB COMMENT ='提币交易记录（构造→签名→广播→追踪→加速）';

-- ⚠ 本表用 CREATE TABLE IF NOT EXISTS，对已存在的库不会补列；Day 5-7 新增 accelerate_count，
--   旧库需手工执行一次：ALTER TABLE withdraw_record ADD COLUMN accelerate_count INT NOT NULL DEFAULT 0 COMMENT '已提价重播次数' AFTER gas_price;

-- V1__account.sql
-- 账户余额表：一个用户 × 一个账户类型 × 一个币种 = 一行
CREATE TABLE IF NOT EXISTS `account` (
                           `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
                           `user_id`       BIGINT UNSIGNED NOT NULL                COMMENT '用户ID',
                           `account_type`  VARCHAR(16)     NOT NULL                COMMENT '账户类型: FUND/SPOT/PERP；PLATFORM=平台账户（代收负债/手续费/待结算，允许负余额）',
                           `currency`      VARCHAR(16)     NOT NULL                COMMENT '币种符号: USDT/ETH/BTC',
                           `available`     DECIMAL(38,18)  NOT NULL DEFAULT 0      COMMENT '可用余额',
                           `frozen`        DECIMAL(38,18)  NOT NULL DEFAULT 0      COMMENT '冻结余额',
                           `total`         DECIMAL(38,18)  GENERATED ALWAYS AS (`available` + `frozen`) STORED
                                                          COMMENT '总额=可用+冻结，生成列，应用不可写',
                           `version`       BIGINT UNSIGNED NOT NULL DEFAULT 0      COMMENT '乐观锁版本号，CAS 用',
                           `created_at`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                           `updated_at`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
                           PRIMARY KEY (`id`),
                           UNIQUE KEY `uk_user_type_currency` (`user_id`, `account_type`, `currency`),
                           KEY `idx_user_type` (`user_id`, `account_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='账户余额表（复式账本的余额物化）';
ALTER TABLE `account`
    ADD CONSTRAINT `chk_total_balance` CHECK (`total` = `available` + `frozen`);

-- 划转单：一次账户间划转 = 一行
CREATE TABLE IF NOT EXISTS `account_transfer` (
                                    `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
                                    `transfer_no`      VARCHAR(64)     NOT NULL                COMMENT '业务单号，幂等键',
                                    `from_account_id`  BIGINT UNSIGNED NOT NULL                COMMENT '转出账户ID',
                                    `to_account_id`    BIGINT UNSIGNED NOT NULL                COMMENT '转入账户ID',
                                    `currency`         VARCHAR(16)     NOT NULL                COMMENT '币种',
                                    `amount`           DECIMAL(38,18)  NOT NULL                COMMENT '划转金额',
                                    `status`           VARCHAR(16)     NOT NULL DEFAULT 'INIT' COMMENT 'INIT/SUCCESS/FAILED',
                                    `created_at`       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                                    `updated_at`       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
                                    PRIMARY KEY (`id`),
                                    UNIQUE KEY `uk_transfer_no` (`transfer_no`),
                                    KEY `idx_from_account` (`from_account_id`),
                                    KEY `idx_to_account` (`to_account_id`),
                                    KEY `idx_status_created` (`status`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='账户间划转单，业务事实层，配合 ledger_entry/ledger_posting 使用';


-- 合约持仓：一个用户 × 一个 symbol × 一个方向 × 一个保证金模式 = 一行
CREATE TABLE IF NOT EXISTS `perp_position` (
                                 `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
                                 `user_id`       BIGINT UNSIGNED NOT NULL                COMMENT '用户ID',
                                 `symbol`        VARCHAR(32)     NOT NULL                COMMENT '交易对: BTCUSDT',
                                 `side`          VARCHAR(8)      NOT NULL                COMMENT 'LONG/SHORT',
                                 `qty`           DECIMAL(38,18)  NOT NULL DEFAULT 0      COMMENT '持仓数量',
                                 `entry_price`   DECIMAL(38,18)  NOT NULL DEFAULT 0      COMMENT '开仓均价',
                                 `margin`        DECIMAL(38,18)  NOT NULL DEFAULT 0      COMMENT '占用保证金',
                                 `margin_mode`   VARCHAR(16)     NOT NULL DEFAULT 'CROSS' COMMENT 'CROSS/ISOLATED',
                                 `leverage`      DECIMAL(8,2)    NOT NULL DEFAULT 1      COMMENT '杠杆倍数，如 10.00',
                                 `version`       BIGINT UNSIGNED NOT NULL DEFAULT 0,
                                 `created_at`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                                 `updated_at`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
                                 PRIMARY KEY (`id`),
                                 UNIQUE KEY `uk_user_symbol_side_mode` (`user_id`, `symbol`, `side`, `margin_mode`),
                                 KEY `idx_user_symbol` (`user_id`, `symbol`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='合约持仓表，独立于 account 余额表（持仓有方向/杠杆/保证金模式，塞进余额表会废掉聚合查询）';
-- ledger_entry：分录组
CREATE TABLE IF NOT EXISTS `ledger_entry` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `entry_no`    VARCHAR(64)     NOT NULL                COMMENT '账本单号，全局唯一',
  `biz_type`    VARCHAR(32)     NOT NULL                COMMENT '业务类型: DEPOSIT/TRANSFER/FREEZE/UNFREEZE/FEE/REVERSE',
  `biz_no`      VARCHAR(128)    NOT NULL                COMMENT '业务单号，与 biz_type 联合幂等',
  `status`      VARCHAR(16)     NOT NULL DEFAULT 'SUCCESS' COMMENT 'SUCCESS/REVERSED',
  `reverse_of` BIGINT UNSIGNED NULL COMMENT '冲正时指向原 entry_id，原分录不改',
  `created_at`  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_entry_no` (`entry_no`),
  UNIQUE KEY `uk_biz` (`biz_type`, `biz_no`),
  -- 一条原分录最多被冲正一次：MySQL 唯一索引允许多个 NULL（普通业务行），冲正行 reverse_of 非空
  UNIQUE KEY `uk_reverse_of` (`reverse_of`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分录组：一笔业务一行，幂等键在组上';

-- ledger_posting：分录明细
CREATE TABLE IF NOT EXISTS `ledger_posting` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `entry_id`       BIGINT UNSIGNED NOT NULL                COMMENT '所属分录组',
  `account_id`     BIGINT UNSIGNED NOT NULL                COMMENT '账户ID，重建余额的分组键',
  `direction`      VARCHAR(8)      NOT NULL                COMMENT 'DEBIT/CREDIT，CREDIT=余额增加、DEBIT=余额减少',
  `balance_type`   VARCHAR(16)     NOT NULL DEFAULT 'AVAILABLE' COMMENT 'AVAILABLE/FROZEN，指明本次变动的是哪个子账',
  `amount`         DECIMAL(38,18)  NOT NULL                COMMENT '金额，永远正数',
  `currency`       VARCHAR(16)     NOT NULL                COMMENT '币种',
  `balance_after`  DECIMAL(38,18)  NOT NULL                COMMENT '本笔操作后该子账余额，审计用',
  `created_at`     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_entry` (`entry_id`),
  KEY `idx_account_created` (`account_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分录明细：一个账户一行，条数不定';

-- ⚠ 同 withdraw_record：本脚本用 CREATE TABLE IF NOT EXISTS，对已存在的库不会补列/补索引。
--   若 ledger_posting 已按旧版建过表，需手工执行一次：
--   ALTER TABLE ledger_posting ADD COLUMN balance_type VARCHAR(16) NOT NULL DEFAULT 'AVAILABLE'
--       COMMENT 'AVAILABLE/FROZEN，指明本次变动的是哪个子账' AFTER direction;
--   若 ledger_entry 未建 uk_reverse_of（Day 5-6 冲正需要），手工执行：
--   ALTER TABLE ledger_entry ADD UNIQUE KEY uk_reverse_of (reverse_of);

-- 生产库账号权限：账本只增不改（配合代码侧“不提供 update/delete 方法”）
--   GRANT SELECT, INSERT ON web3_backend.ledger_entry   TO 'ledger_app'@'%';
--   GRANT SELECT, INSERT ON web3_backend.ledger_posting TO 'ledger_app'@'%';
--   GRANT SELECT, INSERT, UPDATE ON web3_backend.account TO 'ledger_app'@'%';  -- 仅余额回写，不给 DELETE
