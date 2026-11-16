package com.ray.web3.service.impl;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.ray.web3.config.DepositConsumerProperties;
import com.ray.web3.ledger.mapper.AccountMapper;
import com.ray.web3.mapper.ConsumerBizMapper;
import com.ray.web3.model.entity.Account;
import com.ray.web3.model.entity.LedgerEntry;
import com.ray.web3.model.vo.DepositEventVO;
import com.ray.web3.service.LedgerService;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 消费幂等 + 事务回滚的数据库级验证（第 3 周 Day 5-6）
 * <p>
 * 用 H2 MySQL 兼容模式跑真实 {@code INSERT IGNORE consumer_biz}：
 * <ul>
 *   <li>账本入账失败 → 外层事务回滚 consumer_biz，Kafka 重投时可以重新处理；</li>
 *   <li>同一 depositNo 第二次消费 → INSERT IGNORE 返回 0，不重复调账本。</li>
 * </ul>
 */
class DepositCreditServiceTransactionTest {

    private static final String USDT = "USDT";
    private static final String DEPOSIT_NO = "DP1";

    private JdbcTemplate jdbc;
    private TransactionTemplate txTemplate;
    private ConsumerBizMapper consumerBizMapper;
    private LedgerService ledgerService;
    private DepositCreditService service;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:consumer_tx_" + System.nanoTime()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE consumer_biz (
                  biz_no VARCHAR(64) PRIMARY KEY,
                  consumed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);

        SqlSessionFactory factory = buildSqlSessionFactory(dataSource);
        consumerBizMapper = new SqlSessionTemplate(factory).getMapper(ConsumerBizMapper.class);

        AccountMapper accountMapper = mock(AccountMapper.class);
        when(accountMapper.selectByUserTypeCurrency(10001L, "SPOT", USDT)).thenReturn(account());
        ledgerService = mock(LedgerService.class);

        DepositConsumerProperties properties = new DepositConsumerProperties();
        properties.setPlatformLiabilityAccountId(900101L);
        service = new DepositCreditService(consumerBizMapper, accountMapper, ledgerService,
                new ObjectMapper(), properties);
        txTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Test
    void 账本失败时consumer_biz随事务回滚_重投可再次处理() {
        when(ledgerService.deposit(anyString(), anyLong(), anyLong(), any(), anyString()))
                .thenThrow(new IllegalStateException("ledger down"))
                .thenReturn(entry());

        // 第一次：账本失败 → 外层事务回滚 consumer_biz
        assertThatThrownBy(() -> txTemplate.executeWithoutResult(status ->
                service.credit(payload())))
                .isInstanceOf(RuntimeException.class);
        assertThat(count()).as("失败不得留下已消费标记").isZero();

        // 第二次（模拟 Kafka 重投）：幂等表为空，重新处理成功
        DepositCreditService.CreditResult result = txTemplate.execute(status -> service.credit(payload()));
        assertThat(result).isNotNull();
        assertThat(result.duplicate()).isFalse();
        assertThat(result.entryNo()).isEqualTo("LE1");
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void 同一depositNo第二次消费只ack不再调账本() {
        when(ledgerService.deposit(anyString(), anyLong(), anyLong(), any(), anyString()))
                .thenReturn(entry());

        DepositCreditService.CreditResult first = txTemplate.execute(status -> service.credit(payload()));
        DepositCreditService.CreditResult second = txTemplate.execute(status -> service.credit(payload()));

        assertThat(first).isNotNull();
        assertThat(first.duplicate()).isFalse();
        assertThat(second).isNotNull();
        assertThat(second.duplicate()).isTrue();
        assertThat(second.entryNo()).isNull();
        assertThat(count()).isEqualTo(1);
        verify(ledgerService, times(1)).deposit(anyString(), anyLong(), anyLong(), any(), anyString());
    }

    private int count() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM consumer_biz", Integer.class);
        return count == null ? 0 : count;
    }

    private static Account account() {
        Account account = new Account();
        account.setId(900001L);
        account.setUserId(10001L);
        account.setAccountType("SPOT");
        account.setCurrency(USDT);
        return account;
    }

    private static LedgerEntry entry() {
        LedgerEntry entry = new LedgerEntry();
        entry.setEntryNo("LE1");
        entry.setBizType("DEPOSIT");
        entry.setBizNo(DEPOSIT_NO);
        return entry;
    }

    private String payload() {
        DepositEventVO event = new DepositEventVO(
                DEPOSIT_NO, 31337L, "0x5fbdb2315678afecb367f032d93f642f64180aa3",
                USDT, "0xaaa1", 7L, 100L,
                "0x95222290dd7278aa3ddd389b1e2d9d2f0f4b0001",
                "0x3c44cdddb6a900fa2b585dd299e03d12fa4293bc",
                "USER", "10001", "100", "100000000", 6,
                "PENDING_CONFIRM", 12L, "STREAM",
                LocalDateTime.of(2026, 11, 16, 10, 0));
        return new ObjectMapper().writeValueAsString(event);
    }

    private static SqlSessionFactory buildSqlSessionFactory(JdbcDataSource dataSource) throws Exception {
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(ConsumerBizMapper.class);
        factory.setConfiguration(configuration);

        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setDbConfig(new GlobalConfig.DbConfig().setIdType(IdType.AUTO));
        factory.setGlobalConfig(globalConfig);
        return factory.getObject();
    }
}