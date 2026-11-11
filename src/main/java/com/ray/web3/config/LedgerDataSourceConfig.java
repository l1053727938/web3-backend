package com.ray.web3.config;

import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import javax.sql.DataSource;

/**
 * 账本数据源（连接池隔离的「账本池」）
 * <p>
 * <b>为什么隔离</b>：账本是资金链路，{@code post()} 拿不到连接 = 充值/提币的余额变动全部卡死。
 * 与业务共用一个池时，业务侧一条慢 SQL（或一次流量尖峰）占满连接，账本就被连坐。
 * 独立池（默认 10）保证即使业务池被打爆，记账仍能拿到连接。
 * <p>
 * <b>同一个库，两个池</b>：默认仍指向 {@code web3_backend}，隔离的是「连接」而不是「数据」；
 * 以后要物理分库，只需把 {@code spring.datasource.ledger.jdbc-url} 指到另一个实例，代码零改动。
 * <p>
 * <b>路由靠包名</b>：{@code com.ray.web3.ledger.mapper} 下的 Mapper 绑定本工厂，
 * 其余 Mapper 留在 {@code com.ray.web3.mapper} 走业务池。把账本 Mapper 挪回业务包 = 隔离失效。
 * <p>
 * {@code LedgerServiceImpl} 必须显式写
 * {@code @Transactional(transactionManager = "ledgerTransactionManager")}：
 * 默认事务管理器是业务池的，绑定错了就会出现“账本 SQL 在业务连接上、事务锁在账本连接上”的错乱。
 */
@Configuration
@MapperScan(basePackages = "com.ray.web3.ledger.mapper", sqlSessionFactoryRef = "ledgerSqlSessionFactory")
public class LedgerDataSourceConfig {

    /** 账本池：绑定 {@code spring.datasource.ledger.*}，Hikari 属性名与 yml 键一一对应 */
    @Bean
    @ConfigurationProperties("spring.datasource.ledger")
    public HikariDataSource ledgerDataSource() {
        return new HikariDataSource();
    }

    @Bean
    public SqlSessionFactory ledgerSqlSessionFactory(
            @Qualifier("ledgerDataSource") DataSource ledgerDataSource) throws Exception {
        return MybatisFactorySupport.build(ledgerDataSource);
    }

    @Bean
    public SqlSessionTemplate ledgerSqlSessionTemplate(
            @Qualifier("ledgerSqlSessionFactory") SqlSessionFactory ledgerSqlSessionFactory) {
        return new SqlSessionTemplate(ledgerSqlSessionFactory);
    }

    /**
     * 账本事务管理器：只管理账本池的连接事务。
     * 名字 {@code ledgerTransactionManager} 是 {@code LedgerServiceImpl} 上注解的引用目标，改名要同步改。
     */
    @Bean
    public DataSourceTransactionManager ledgerTransactionManager(
            @Qualifier("ledgerDataSource") DataSource ledgerDataSource) {
        return new DataSourceTransactionManager(ledgerDataSource);
    }
}
