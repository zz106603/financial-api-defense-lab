package lab.financialapi.persistence;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
public abstract class PostgresIntegrationSupport {
    // JVM 종료까지 공유하며 Ryuk이 정리한다. 클래스 간 컨텍스트의 닫힌 DB 재사용을 방지한다.
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.1")
            .withDatabaseName("financial_persistence_test");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
