package com.ray.web3.config;

import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import javax.sql.DataSource;

/**
 * 业务数据源（连接池隔离的「主池」）
 * <p>
 * 一旦应用里出现第二个 {@link DataSource}  Bean，Spring Boot 的
 * {@code DataSourceAutoConfiguration} 就会整体退让（{@code @ConditionalOnMissingBean}），
 * 所以主池必须在这里显式声明，不能指望自动配置。
 * <p>
 * 所有不加限定的 {@code @Transactional} / {@code JdbcTemplate} / 健康检查默认落到这个池。
 * 账本池见 {@link LedgerDataSourceConfig}。
 */
@Configuration
public class DataSourceConfig {

    /**
     * 业务池：绑定 {@code spring.datasource.*}（url/账号/密码）+ {@code spring.datasource.hikari.*}（池参数）
     */
    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource dataSource(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    @Bean
    @Primary
    public SqlSessionFactory sqlSessionFactory(@Qualifier("dataSource") DataSource dataSource) throws Exception {
        return MybatisFactorySupport.build(dataSource);
    }

    @Bean
    @Primary
    public SqlSessionTemplate sqlSessionTemplate(
            @Qualifier("sqlSessionFactory") SqlSessionFactory sqlSessionFactory) {
        return new SqlSessionTemplate(sqlSessionFactory);
    }

    /**
     * 默认事务管理器：业务侧裸 {@code @Transactional} 用的就是它，显式定义是为了不让它
     * 被账本事务管理器的存在挤掉（Boot 的自动配置同样是 {@code @ConditionalOnMissingBean}）
     */
    @Bean
    @Primary
    public DataSourceTransactionManager transactionManager(
            @Qualifier("dataSource") DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }
}
