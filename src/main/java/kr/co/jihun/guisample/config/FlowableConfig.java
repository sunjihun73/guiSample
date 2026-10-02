package kr.co.jihun.guisample.config;

import com.zaxxer.hikari.HikariDataSource;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.flowable.spring.boot.EngineConfigurationConfigurer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * Flowable(워크플로우 엔진) 전용 DataSource 구성.
 *
 * <p><b>왜 별도 DataSource 인가</b> — 엔진이 만드는 ACT_* 테이블(60여 개)을 RAG/도메인 DB 와
 * 섞지 않는다. 운영 환경에도 워크플로우 DB 가 {@code flowable-db}(5433/flowable_test) 로
 * 따로 떠 있어 거기에 맞춘다. 앱의 {@code @Primary} DataSource(pgvector)는 그대로 둔다.
 *
 * <p><b>주의</b> — 그래서 <b>도메인 데이터와 워크플로우 상태는 같은 트랜잭션에 묶이지 않는다.</b>
 * 태스크 진행과 도메인 테이블 갱신을 함께 해야 하는 기능이 생기면, 엔진 쪽을 먼저 커밋하고
 * 실패 시 보상하는 식으로 순서를 명시해야 한다(2PC 를 쓰지 않는다).
 *
 * <p><b>오토컨피그와의 관계</b> — Flowable 스타터의 {@code ProcessEngineAutoConfiguration} 은
 * {@code @Primary} DataSource 와 트랜잭션 매니저를 주입받아 엔진 설정을 만든다. 여기서
 * {@link EngineConfigurationConfigurer} 로 그 둘을 전용 빈으로 바꿔 끼운 뒤 엔진이 빌드된다.
 * 스키마 생성/BPMN 자동 배포 등 나머지 동작은 오토컨피그 기본값을 그대로 쓴다.
 */
@Configuration
public class FlowableConfig
{
    /** 워크플로우 전용 커넥션 풀. 설정은 {@code flowable.datasource} (Hikari 키). */
    @Bean
    @ConfigurationProperties("flowable.datasource")
    public HikariDataSource flowableDataSource()
    {
        return new HikariDataSource();
    }

    /**
     * 워크플로우 전용 트랜잭션 매니저.
     *
     * <p>이 빈이 생기면 Boot 의 트랜잭션 매니저 자동구성이 물러나므로
     * 앱 기본 트랜잭션 매니저는 {@code DataSourceConfig} 에서 {@code @Primary} 로 직접 선언한다.
     * 그렇게 하지 않으면 {@code @Transactional} 이 워크플로우 DB 를 잡는다.
     */
    @Bean
    public PlatformTransactionManager flowableTransactionManager(
            @Qualifier("flowableDataSource") DataSource flowableDataSource)
    {
        return new DataSourceTransactionManager(flowableDataSource);
    }

    /** 엔진이 빌드되기 전에 DataSource/트랜잭션 매니저를 워크플로우 전용 빈으로 교체한다. */
    @Bean
    public EngineConfigurationConfigurer<SpringProcessEngineConfiguration> flowableDataSourceConfigurer(
            @Qualifier("flowableDataSource") DataSource flowableDataSource,
            @Qualifier("flowableTransactionManager") PlatformTransactionManager flowableTransactionManager)
    {
        return configuration ->
        {
            configuration.setDataSource(flowableDataSource);
            configuration.setTransactionManager(flowableTransactionManager);
        };
    }
}
