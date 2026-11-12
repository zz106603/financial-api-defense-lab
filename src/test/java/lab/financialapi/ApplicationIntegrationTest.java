package lab.financialapi;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class ApplicationIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.1")
            .withDatabaseName("financial_api_test");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    SqlSessionFactory sqlSessionFactory;

    @Autowired
    Flyway flyway;

    @Autowired
    ServletWebServerApplicationContext application;

    @Test
    void MyBatis가_격리된_PostgreSQL에_연결되고_Flyway가_초기화된다() throws Exception {
        try (var session = sqlSessionFactory.openSession();
                var statement = session.getConnection().createStatement();
                var result = statement.executeQuery(
                        "SELECT current_database(), to_regclass('public.flyway_schema_history')")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualTo("financial_api_test");
            assertThat(result.getString(2)).isEqualTo("flyway_schema_history");
            assertThat(session.getConnection().getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
        }
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
    }

    @Test
    void 웹서버가_기동되고_매핑되지_않은_경로는_404를_반환한다() throws Exception {
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + application.getWebServer().getPort() + "/__bootstrap_test__"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            var response = client.send(request, HttpResponse.BodyHandlers.discarding());

            assertThat(response.statusCode()).isEqualTo(404);
        }
    }
}
