package lab.financialapi.authorization;

import java.util.UUID;

import lab.financialapi.identity.JwtTestTokens;
import lab.financialapi.persistence.PostgresIntegrationSupport;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** 실제 서명 JWT·실제 커밋을 사용하는 권한 변경 테스트의 공통 진입점. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Sql(scripts = {"/fixtures/clear-financial-data.sql", "/fixtures/financial-data.sql"})
@Sql(scripts = "/fixtures/clear-financial-data.sql", executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
abstract class GrantApiSupport extends PostgresIntegrationSupport {
    static final String A1 = "30000000-0000-4000-8000-000000000001";
    static final String A2 = "30000000-0000-4000-8000-000000000002";
    static final String B1 = "30000000-0000-4000-8000-000000000003";
    static final String MISSING = "30000000-0000-4000-8000-000000000099";
    static final String X = "20000000-0000-4000-8000-000000000001";
    static final String Y = "20000000-0000-4000-8000-000000000002";
    static final String INITIAL = "50000000-0000-4000-8000-000000000001";
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    MockMvc mvc;

    @DynamicPropertySource
    static void jwtProperties(DynamicPropertyRegistry registry) {
        JwtTestTokens.registerProperties(registry);
    }

    @BeforeEach
    void configureMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    String body(String partner, String permission) {
        return "{\"partnerId\":\"" + partner + "\",\"permission\":\"" + permission + "\"}";
    }

    ResultActions grant(String sub, String type, String account, String body) throws Exception {
        return mvc.perform(post("/api/v1/accounts/" + account + "/grants")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + JwtTestTokens.token(sub, type))
                .header("X-Request-Id", "untrusted-client-id")
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    ResultActions revoke(String sub, String type, String account, String grant) throws Exception {
        return mvc.perform(delete("/api/v1/accounts/" + account + "/grants/" + grant)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + JwtTestTokens.token(sub, type)));
    }

    int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    Object revokedAt(String grant) {
        return jdbc.queryForObject("SELECT revoked_at FROM access_grant WHERE id = ?", Object.class,
                UUID.fromString(grant));
    }
}
