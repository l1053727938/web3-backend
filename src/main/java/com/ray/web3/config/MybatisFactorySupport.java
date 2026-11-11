package com.ray.web3.config;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.logging.slf4j.Slf4jImpl;
import org.apache.ibatis.session.SqlSessionFactory;

import javax.sql.DataSource;

/**
 * 多数据源下 {@link SqlSessionFactory} 的统一构造（业务池 / 账本池各一份）
 * <p>
 * 为什么必须手写：MyBatis-Plus 自动配置的 {@code sqlSessionFactory} 是
 * {@code @ConditionalOnMissingBean}，一旦账本池也定义一个工厂，自动配置就会整体退让，
 * 业务 Mapper 会失去工厂。所以两个都显式建，且用同一段代码避免两池行为漂移。
 * <p>
 * 这里的配置与 {@code application.yml} 的 {@code mybatis-plus.*} 保持等价
 * （下划线转驼峰、sql 日志实现、主键自增），改 yml 时记得同步改这里。
 */
final class MybatisFactorySupport {

    private MybatisFactorySupport() {
    }

    static SqlSessionFactory build(DataSource dataSource) throws Exception {
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setLogImpl(Slf4jImpl.class);
        factory.setConfiguration(configuration);

        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setDbConfig(new GlobalConfig.DbConfig().setIdType(IdType.AUTO));
        factory.setGlobalConfig(globalConfig);

        return factory.getObject();
    }
}
