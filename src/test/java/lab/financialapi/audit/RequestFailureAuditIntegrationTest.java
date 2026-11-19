package lab.financialapi.audit;

import java.util.UUID;
import java.net.URI;
import java.util.stream.Stream;

import lab.financialapi.identity.JwtTestTokens;
import lab.financialapi.persistence.PostgresIntegrationSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Sql(scripts = {"/fixtures/clear-financial-data.sql", "/fixtures/financial-data.sql"})
@Sql(scripts = "/fixtures/clear-financial-data.sql", executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
class RequestFailureAuditIntegrationTest extends PostgresIntegrationSupport {
    static final String ACCOUNT = "30000000-0000-4000-8000-000000000001";
    static final String PATH = "/api/v1/accounts/" + ACCOUNT;
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    MockMvc mvc;

    @DynamicPropertySource
    static void jwtProperties(DynamicPropertyRegistry registry) {
        JwtTestTokens.registerProperties(registry);
    }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @ParameterizedTest
    @MethodSource("protectedRequests")
    void 미인증_금융_API는_익명_주체와_정확한_행위로_한번만_감사한다(
            String method, String suffix, String action) throws Exception {
        var request = switch (method) {
            case "POST" -> post(PATH + suffix);
            case "DELETE" -> delete(PATH + suffix);
            default -> get(PATH + suffix);
        };
        var response = mvc.perform(request.header("X-Request-Id", "untrusted"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andReturn().getResponse();
        var event = jdbc.queryForMap("SELECT * FROM audit_event");
        assertThat(event.get("principal_type")).isEqualTo("ANONYMOUS");
        assertThat(event.get("principal_id")).isNull();
        assertThat(event.get("action")).isEqualTo(action);
        assertThat(event.get("reason_code")).isEqualTo("AUTHENTICATION_FAILED");
        assertThat(event.get("result")).isEqualTo("DENIED");
        assertThat(event.get("resource_ref").toString()).matches("account:v1:[0-9a-f]{64}");
        assertThat(event.get("request_id")).isEqualTo(response.getHeader("X-Request-Id")).isNotEqualTo("untrusted");
    }

    static Stream<Arguments> protectedRequests() {
        return Stream.of(Arguments.of("GET", "", "ACCOUNT_READ"),
                Arguments.of("GET", "/transactions", "TRANSACTION_READ"),
                Arguments.of("POST", "/grants", "GRANT_CREATE"),
                Arguments.of("DELETE", "/grants/50000000-0000-4000-8000-000000000001", "GRANT_REVOKE"));
    }

    @Test
    void 잘못된_서명과_미등록_sub를_감사_주체로_사용하지_않는다() throws Exception {
        for (String token : new String[]{JwtTestTokens.token("customer-a", "CUSTOMER") + "tampered",
                JwtTestTokens.token("unknown-sub", "CUSTOMER")}) {
            mvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isUnauthorized());
        }
        assertThat(jdbc.queryForList("SELECT principal_id FROM audit_event", UUID.class)).containsOnlyNulls().hasSize(2);
        assertThat(jdbc.queryForList("SELECT * FROM audit_event").toString())
                .doesNotContain("customer-a", "unknown-sub", "Bearer", "tampered", ACCOUNT);
    }

    @Test
    void 잘못된_UUID와_페이지와_본문_권한값은_검증된_주체로_입력_오류_감사한다() throws Exception {
        var requests = new MockHttpServletRequestBuilder[]{
                get("/api/v1/accounts/1-1-1-1-1"),
                get(PATH + "/transactions?page=-1"),
                get(PATH + "/transactions?page=secret-input"),
                post(PATH + "/grants").contentType(MediaType.APPLICATION_JSON).content("{"),
                post(PATH + "/grants").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"partnerId\":\"20000000-0000-4000-8000-000000000002\",\"permission\":0}")
        };
        for (var request : requests) {
            var response = mvc.perform(authenticated(request))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                    .andReturn().getResponse();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE request_id = ?", Integer.class,
                    response.getHeader("X-Request-Id"))).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE reason_code = 'INVALID_REQUEST'", Integer.class))
                .isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE resource_ref IS NULL", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT principal_id FROM audit_event", UUID.class))
                .containsOnly(UUID.fromString("10000000-0000-4000-8000-000000000001"));
        assertThat(jdbc.queryForList("SELECT * FROM audit_event").toString())
                .doesNotContain("secret-input", "1-1-1-1-1", "permission", "Bearer", "customer-a", ACCOUNT);
    }

    @Test
    void 새_감사_INSERT_실패는_401과_400을_유지하고_기존_필수_감사만_503이다() throws Exception {
        jdbc.execute("ALTER TABLE audit_event RENAME TO unavailable_error_audit");
        try {
            mvc.perform(get(PATH)).andExpect(status().isUnauthorized())
                    .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")));
            mvc.perform(authenticated(get(PATH + "/transactions?size=0")))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
            mvc.perform(authenticated(get(PATH))).andExpect(status().isServiceUnavailable());
        } finally {
            jdbc.execute("ALTER TABLE unavailable_error_audit RENAME TO audit_event");
        }
    }

    @Test
    void 새_감사_커밋_실패도_원래_401과_400을_유지한다() throws Exception {
        jdbc.execute("CREATE FUNCTION test_fail_error_audit() RETURNS trigger LANGUAGE plpgsql AS $$ "
                + "BEGIN RAISE EXCEPTION 'synthetic commit failure'; RETURN NEW; END $$");
        jdbc.execute("CREATE CONSTRAINT TRIGGER test_fail_error_audit AFTER INSERT ON audit_event "
                + "DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION test_fail_error_audit()");
        try {
            mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
            mvc.perform(authenticated(get(PATH + "/transactions?size=0"))).andExpect(status().isBadRequest());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event", Integer.class)).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER test_fail_error_audit ON audit_event");
            jdbc.execute("DROP FUNCTION test_fail_error_audit()");
        }
    }

    @Test
    void 호출측_업무가_롤백되어도_입력_오류_감사는_남는다() {
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            try {
                mvc.perform(authenticated(get(PATH + "/transactions?page=-1"))).andExpect(status().isBadRequest());
            } catch (Exception error) {
                throw new AssertionError(error);
            }
            tx.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event", Integer.class)).isEqualTo(1);
    }

    @Test
    void 범위밖_경로와_메서드는_새_감사에_포함하지_않는다() throws Exception {
        mvc.perform(get("/unmapped")).andExpect(status().isUnauthorized());
        mvc.perform(get(PATH + "/grants")).andExpect(status().isUnauthorized());
        mvc.perform(post(PATH)).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event", Integer.class)).isZero();
    }

    @ParameterizedTest
    @MethodSource("encodedPaths")
    void MVC가_인식하는_인코딩된_금융_경로도_감사와_가명_대상을_유지한다(String path) throws Exception {
        mvc.perform(authenticated(get(URI.create(path + "?size=0"))))
                .andExpect(status().isBadRequest());
        mvc.perform(get(URI.create(path))).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE resource_ref IS NOT NULL", Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT resource_ref) FROM audit_event", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT action FROM audit_event", String.class)).containsOnly("TRANSACTION_READ");
    }

    static Stream<String> encodedPaths() {
        return Stream.of("/%61pi/v1/accounts/" + ACCOUNT + "/transactions",
                "/api/v1/accounts/%33" + ACCOUNT.substring(1) + "/transactions");
    }

    private MockHttpServletRequestBuilder authenticated(MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + JwtTestTokens.token("customer-a", "CUSTOMER"));
    }
}
