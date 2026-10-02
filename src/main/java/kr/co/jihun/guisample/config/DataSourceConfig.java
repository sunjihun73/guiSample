package kr.co.jihun.guisample.config;

import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * Single PostgreSQL datasource shared by both Spring AI pgvector and the MyBatis
 * mappers in {@code kr.co.jihun.guisample.mapper}.
 * <p>
 * Declaring our own {@link DataSource} bean disables Spring Boot's datasource and
 * MyBatis auto-configuration, so both must be wired explicitly here.
 */
@Configuration
@MapperScan(
        basePackages = "kr.co.jihun.guisample.mapper",
        sqlSessionFactoryRef = "sqlSessionFactory")
public class DataSourceConfig
{
    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    public HikariDataSource dataSource()
    {
        return new HikariDataSource();
    }

    /**
     * 앱 기본 트랜잭션 매니저 — {@code @Transactional} 이 잡는 DataSource 를 이 DataSource 로 못 박는다.
     *
     * <p>Flowable 이 워크플로우 전용 트랜잭션 매니저 빈({@code FlowableConfig})을 추가하면
     * Boot 의 트랜잭션 매니저 자동구성이 물러난다. 그때 @Primary 가 없으면 도메인 서비스의
     * {@code @Transactional} 이 워크플로우 DB 를 잡게 되므로 여기서 명시적으로 선언한다.
     */
    @Bean
    @Primary
    public PlatformTransactionManager transactionManager(DataSource dataSource)
    {
        return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    public SqlSessionFactory sqlSessionFactory(
            DataSource dataSource,
            ApplicationContext applicationContext) throws Exception
    {
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setMapperLocations(applicationContext.getResources("classpath:mapper/**/*.xml"));
        factory.setTypeAliasesPackage("kr.co.jihun.guisample.dto");

        org.apache.ibatis.session.Configuration mybatisConfig = new org.apache.ibatis.session.Configuration();
        mybatisConfig.setMapUnderscoreToCamelCase(true);
        factory.setConfiguration(mybatisConfig);

        return factory.getObject();
    }
}