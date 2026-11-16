package com.ray.web3.service.impl;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.ray.web3.config.DepositProperties;
import com.ray.web3.config.InfuraProperties;
import com.ray.web3.consts.DepositConsts;
import com.ray.web3.mapper.DepositRecordMapper;
import com.ray.web3.mapper.OutboxMessageMapper;
import com.ray.web3.model.dto.TransferEventDTO;
import com.ray.web3.model.entity.DepositWallet;
import com.ray.web3.service.DepositWalletService;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.math.BigInteger;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 充值记录与 Outbox 同事务的数据库级验证（第 3 周 Day 3-4）
 * <p>
 * 用 H2 MySQL 兼容模式跑真实 SQL，验证 Mock 单测覆盖不到的核心保证：
 * <ul>
 *   <li>正常路径：{@code deposit_record} 与 {@code outbox_message} 一起提交；</li>
 *   <li>业务提交后 Outbox 写入失败：整个事务回滚，<b>deposit_record 与 outbox_message 都为空</b>。</li>
 * </ul>
 * 不依赖真实 MySQL/Redis；H2 仅用于测试。
 */
class DepositRoutingOutboxTransactionTest {

    private static final long CHAIN_ID = 1L;
    private static final String HOT_WALLET = "0x0fd04a68d3c3a692d6fa30384d1a87ef93554ee6";
    private static final String USDT = "0xdac17f958d2ee523a2206206994597c13d831ec7";
    private static final String TX_HASH = "0xaaa1";

    private JdbcTemplate jdbc;
    private TransactionTemplate txTemplate;
    private DepositRoutingServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:outbox_tx_" + System.nanoTime()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        jdbc = new JdbcTemplate(dataSource);
        createSchema();

        SqlSessionFactory factory = buildSqlSessionFactory(dataSource);
        SqlSessionTemplate template = new SqlSessionTemplate(factory);
        DepositRecordMapper depositRecordMapper = template.getMapper(DepositRecordMapper.class);
        OutboxMessageMapper outboxMessageMapper = template.getMapper(OutboxMessageMapper.class);

        DepositWalletService walletService = mock(DepositWalletService.class);
        when(walletService.match(HOT_WALLET)).thenReturn(hotWallet());
        InfuraProperties infuraProperties = new InfuraProperties();
        infuraProperties.setChainId(CHAIN_ID);
        service = new DepositRoutingServiceImpl(walletService, depositRecordMapper,
                outboxMessageMapper, new ObjectMapper(), infuraProperties, new DepositProperties());

        txTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Test
    void 正常路径充值记录与outbox消息同事务提交() {
        int inserted = txTemplate.execute(status ->
                (int) service.route(List.of(event()), DepositConsts.Source.STREAM));

        assertThat(inserted).isEqualTo(1);
        assertThat(count("deposit_record")).isEqualTo(1);
        assertThat(count("outbox_message")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT payload FROM outbox_message", String.class))
                .contains("\"depositNo\"")
                .contains("\"status\":\"PENDING_CONFIRM\"");
    }

    @Test
    void outbox写入失败时充值记录随事务一起回滚() {
        // 故意删掉 payload 列：充值记录先插入成功，随后 outbox 插入失败，验证前者的回滚
        jdbc.execute("ALTER TABLE outbox_message DROP COLUMN payload");

        assertThatThrownBy(() -> txTemplate.executeWithoutResult(status ->
                service.route(List.of(event()), DepositConsts.Source.STREAM)))
                .isInstanceOf(RuntimeException.class);

        assertThat(count("deposit_record")).as("业务写入必须随 outbox 失败一起回滚").isZero();
        assertThat(count("outbox_message")).isZero();
    }

    private void createSchema() {
        jdbc.execute("""
                CREATE TABLE deposit_record (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  deposit_no VARCHAR(64) NOT NULL,
                  chain_id BIGINT NOT NULL,
                  contract_address VARCHAR(64) NOT NULL,
                  transaction_hash VARCHAR(80) NOT NULL,
                  log_index BIGINT NOT NULL,
                  block_number BIGINT NOT NULL,
                  from_address VARCHAR(64) NOT NULL,
                  to_address VARCHAR(64) NOT NULL,
                  wallet_type VARCHAR(16) NOT NULL,
                  user_id VARCHAR(64),
                  currency VARCHAR(32),
                  raw_value VARCHAR(78) NOT NULL,
                  display_value VARCHAR(80) NOT NULL,
                  decimals INT NOT NULL,
                  status VARCHAR(16) NOT NULL,
                  event_source VARCHAR(16) NOT NULL,
                  notify_status VARCHAR(16),
                  notify_at TIMESTAMP,
                  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                  CONSTRAINT uk_deposit_tx UNIQUE (chain_id, transaction_hash, log_index)
                )
                """);
        jdbc.execute("""
                CREATE TABLE outbox_message (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  biz_type VARCHAR(32) NOT NULL,
                  biz_no VARCHAR(64) NOT NULL,
                  payload VARCHAR(4000) NOT NULL,
                  status VARCHAR(16) NOT NULL DEFAULT 'NEW',
                  retry_count INT NOT NULL DEFAULT 0,
                  next_retry_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
    }

    private static SqlSessionFactory buildSqlSessionFactory(JdbcDataSource dataSource) throws Exception {
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        // 无 Spring 容器时手动注册 Mapper（应用里由 @MapperScan 完成）
        configuration.addMapper(DepositRecordMapper.class);
        configuration.addMapper(OutboxMessageMapper.class);
        factory.setConfiguration(configuration);

        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setDbConfig(new GlobalConfig.DbConfig().setIdType(IdType.AUTO));
        factory.setGlobalConfig(globalConfig);
        return factory.getObject();
    }

    private int count(String table) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }

    private static DepositWallet hotWallet() {
        DepositWallet wallet = new DepositWallet();
        wallet.setChainId(CHAIN_ID);
        wallet.setAddress(HOT_WALLET);
        wallet.setWalletType(DepositConsts.WalletType.HOT);
        wallet.setCurrency("USDT");
        wallet.setStatus(1);
        return wallet;
    }

    private static TransferEventDTO event() {
        return new TransferEventDTO(USDT, BigInteger.valueOf(20_000_000L), TX_HASH, BigInteger.valueOf(7L),
                "0x95222290dd7278aa3ddd389b1e2d9d2f0f4b0001", HOT_WALLET,
                new BigInteger("100000000"), "100.000000", 6);
    }
}