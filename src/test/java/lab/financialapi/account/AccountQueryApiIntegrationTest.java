package lab.financialapi.account;

import java.util.UUID;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 실제 서명 JWT와 커밋 가능한 PostgreSQL을 사용한다. 감사의 독립 커밋을 확인하므로 테스트 트랜잭션은 없다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Sql(scripts = {"/fixtures/clear-financial-data.sql", "/fixtures/financial-data.sql"})
@Sql(scripts = "/fixtures/clear-financial-data.sql", executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
class AccountQueryApiIntegrationTest extends PostgresIntegrationSupport {
    static final String A1 = "30000000-0000-4000-8000-000000000001";
    static final String A2 = "30000000-0000-4000-8000-000000000002";
    static final String B1 = "30000000-0000-4000-8000-000000000003";
    static final String MISSING = "30000000-0000-4000-8000-000000000099";
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    MockMvc mvc;

    @DynamicPropertySource
    static void jwtProperties(DynamicPropertyRegistry registry) {
        JwtTestTokens.registerProperties(registry);
    }

    @BeforeEach
    void configureMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void 고객_본인_계좌는_허용된_네_필드만_반환한다() throws Exception {
        read("customer-a", "CUSTOMER", A1, "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.*", hasSize(4)))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.accountId").value(A1))
                .andExpect(jsonPath("$.bankName").value("Test Bank"))
                .andExpect(jsonPath("$.maskedAccountNumber").value("100-***-001"))
                .andExpect(jsonPath("$.balance").value(1500000.25))
                .andExpect(jsonPath("$.customerId").doesNotExist())
                .andExpect(jsonPath("$.createdAt").doesNotExist());
        read("customer-b", "CUSTOMER", B1, "")
                .andExpect(status().isOk());
    }

    @Test
    void 거래내역은_인가한_계좌로_제한하고_페이지_경계와_금액_시각을_반환한다() throws Exception {
        read("customer-a", "CUSTOMER", A1, "/transactions?page=0&size=1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.*", hasSize(5)))
                .andExpect(jsonPath("$.accountId").value(A1))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.hasNext").value(true))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].*", hasSize(4)))
                .andExpect(jsonPath("$.items[0].transactionId").value("40000000-0000-4000-8000-000000000002"))
                .andExpect(jsonPath("$.items[0].amount").value(-100.25))
                .andExpect(jsonPath("$.items[0].occurredAt").value("2026-11-11T01:30:00Z"))
                .andExpect(jsonPath("$.items[0].accountId").doesNotExist());
        read("customer-a", "CUSTOMER", A1, "/transactions?page=1&size=1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.items[0].transactionId").value("40000000-0000-4000-8000-000000000001"));
    }

    @ParameterizedTest
    @MethodSource("deniedReads")
    void 타인_계좌와_위임_범위_밖_조회는_금융정보_없이_404다(
            String sub,
            String type,
            String account,
            String suffix) throws Exception {
        var response = read(sub, type, account, suffix)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.*", hasSize(3)))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("balance", "maskedAccountNumber", "items", "customer_id", account);
    }

    static Stream<Arguments> deniedReads() {
        return Stream.of(Arguments.of("customer-a", "CUSTOMER", B1, ""),
                Arguments.of("customer-a", "CUSTOMER", B1, "/transactions"),
                Arguments.of("customer-b", "CUSTOMER", A2, ""),
                Arguments.of("customer-b", "CUSTOMER", A2, "/transactions"),
                Arguments.of("partner-x", "PARTNER", A2, ""),
                Arguments.of("partner-x", "PARTNER", A1, "/transactions"),
                Arguments.of("partner-y", "PARTNER", A1, ""),
                Arguments.of("partner-y", "PARTNER", A1, "/transactions"),
                Arguments.of("customer-a", "CUSTOMER", MISSING, ""),
                Arguments.of("customer-a", "CUSTOMER", MISSING, "/transactions"));
    }

    @Test
    void 제휴사_조회_권한은_계좌와_행위별로_독립적이다() throws Exception {
        read("partner-x", "PARTNER", A1, "")
                .andExpect(status().isOk());
        grantTransactionRead(A1);
        read("partner-y", "PARTNER", A1, "/transactions")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)));
        read("partner-y", "PARTNER", A1, "")
                .andExpect(status().isNotFound());
        read("partner-y", "PARTNER", A2, "/transactions")
                .andExpect(status().isNotFound());
        jdbc.update("UPDATE access_grant SET revoked_at = now() WHERE partner_id = '20000000-0000-4000-8000-000000000002'");
        read("partner-y", "PARTNER", A1, "/transactions")
                .andExpect(status().isNotFound());
    }

    @Test
    void 허용된_빈_계좌와_범위_밖_페이지는_200_빈_목록이고_권한_없음과_다르다() throws Exception {
        read("customer-a", "CUSTOMER", A2, "/transactions")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.hasNext").value(false));
        read("customer-a", "CUSTOMER", A1, "/transactions?page=100&size=1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));
        grantTransactionRead(A2);
        read("partner-y", "PARTNER", A2, "/transactions")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    void 회수_커밋_후_같은_JWT로_시작한_새_계좌_조회는_거부한다() throws Exception {
        String jwt = JwtTestTokens.token("partner-x", "PARTNER");
        mvc.perform(get("/api/v1/accounts/" + A1).header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt))
                .andExpect(status().isOk());
        jdbc.update("UPDATE access_grant SET revoked_at = now()");
        mvc.perform(get("/api/v1/accounts/" + A1).header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt))
                .andExpect(status().isNotFound());
    }

    @Test
    void 페이지_기본값과_상한을_적용하고_큰_page도_오버플로하지_않는다() throws Exception {
        read("customer-a", "CUSTOMER", A1, "/transactions")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20));
        read("customer-a", "CUSTOMER", A1, "/transactions?size=100")
                .andExpect(status().isOk());
        read("customer-a", "CUSTOMER", A1, "/transactions?size=101")
                .andExpect(status().isBadRequest());
        read("customer-a", "CUSTOMER", A1, "/transactions?page=2147483647&size=100")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    void 인증_JWT의_추가_ID와_조회_파라미터는_타인_계좌_권한을_만들지_못한다() throws Exception {
        var claims = JwtTestTokens.claims("customer-b", "CUSTOMER");
        claims.put("customer_id", "10000000-0000-4000-8000-000000000001");
        claims.put("scope", "ACCOUNT_READ TRANSACTION_READ");
        mvc.perform(get("/api/v1/accounts/" + A1).param("customerId", "10000000-0000-4000-8000-000000000001")
                        .header("X-Customer-Id", "10000000-0000-4000-8000-000000000001")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + JwtTestTokens.sign(claims)))
                .andExpect(status().isNotFound());
    }

    @Test
    void 미존재와_무권한은_외부_오류가_같고_내부_감사_사유는_다르다() throws Exception {
        read("customer-b", "CUSTOMER", A1, "")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("요청한 리소스를 찾을 수 없습니다."));
        read("customer-b", "CUSTOMER", MISSING, "")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("요청한 리소스를 찾을 수 없습니다."));
        assertThat(jdbc.queryForList("SELECT reason_code FROM audit_event ORDER BY occurred_at", String.class))
                .containsExactly("OBJECT_ACCESS_DENIED", "RESOURCE_NOT_FOUND");
    }

    @Test
    void 감사에는_검증된_주체와_가명_대상과_서버_추적_ID만_기록한다() throws Exception {
        var result = mvc.perform(get("/api/v1/accounts/" + A1).header("X-Request-Id", "client-controlled")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + JwtTestTokens.token("customer-a", "CUSTOMER")))
                .andExpect(status().isOk())
                .andReturn();
        var event = jdbc.queryForMap("SELECT * FROM audit_event");
        assertThat(event.get("principal_id")).isEqualTo(UUID.fromString("10000000-0000-4000-8000-000000000001"));
        assertThat(event.get("result")).isEqualTo("ALLOWED");
        assertThat(event.get("action")).isEqualTo("ACCOUNT_READ");
        assertThat(event.get("resource_ref")).isNotEqualTo(A1);
        assertThat(event.get("request_id")).isEqualTo(result.getResponse().getHeader("X-Request-Id"));
        assertThat(event.get("request_id")).isNotEqualTo("client-controlled");
        assertThat(event.toString()).doesNotContain("customer-a", "100-***-001", "1500000", "Bearer", "합성 입금");
        read("customer-b", "CUSTOMER", A1, "")
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT resource_ref) FROM audit_event", Integer.class)).isEqualTo(1);
    }

    @Test
    void 업무_트랜잭션이_롤백되어도_접근_거부_감사_기록은_유지한다() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            try {
                read("customer-b", "CUSTOMER", A1, "")
                        .andExpect(status().isNotFound());
            } catch (Exception error) {
                throw new AssertionError(error);
            }
            status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE result = 'DENIED'", Integer.class)).isEqualTo(1);
    }

    @Test
    void 거래_조회_허용과_거부도_정확한_행위로_감사하고_원문은_남기지_않는다() throws Exception {
        read("customer-a", "CUSTOMER", A1, "/transactions")
                .andExpect(status().isOk());
        read("partner-x", "PARTNER", A1, "/transactions")
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForList("SELECT action FROM audit_event", String.class))
                .containsExactly("TRANSACTION_READ", "TRANSACTION_READ");
        assertThat(jdbc.queryForList("SELECT * FROM audit_event").toString())
                .doesNotContain("합성 입금", "합성 출금", "50000.10", "-100.25", A1, "partner-x", "customer-a");
    }

    @Test
    void 감사_저장에_실패하면_허용_대상도_거부_대상도_금융정보를_반환하지_않는다() throws Exception {
        jdbc.execute("ALTER TABLE audit_event RENAME TO unavailable_audit_event");
        try {
            for (String suffix : new String[]{"", "/transactions"}) {
                for (String sub : new String[]{"customer-a", "customer-b"}) {
                    String response = read(sub, "CUSTOMER", A1, suffix)
                            .andExpect(status().isServiceUnavailable())
                            .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
                            .andReturn().getResponse().getContentAsString();
                    assertThat(response).doesNotContain("balance", "items", "SQL", "Exception", "unavailable_audit_event");
                }
            }
        } finally {
            jdbc.execute("ALTER TABLE unavailable_audit_event RENAME TO audit_event");
        }
    }

    @Test
    void 금융_조회_DB가_실패하면_SQL이나_데이터를_노출하지_않는다() throws Exception {
        jdbc.execute("ALTER TABLE account RENAME TO unavailable_account");
        try {
            String response = read("customer-a", "CUSTOMER", A1, "")
                    .andExpect(status().isServiceUnavailable())
                    .andReturn().getResponse().getContentAsString();
            assertThat(response).doesNotContain("balance", "SQL", "Exception", "unavailable_account");
        } finally {
            jdbc.execute("ALTER TABLE unavailable_account RENAME TO account");
        }
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void 잘못된_UUID나_페이지_입력은_400이고_입력_원문을_노출하지_않는다(
            String account,
            String suffix) throws Exception {
        read("customer-a", "CUSTOMER", account, suffix)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    static Stream<Arguments> invalidRequests() {
        return Stream.of(Arguments.of("not-a-uuid", ""),
                Arguments.of("1-1-1-1-1", ""),
                Arguments.of("not-a-uuid", "/transactions"),
                Arguments.of(A1, "/transactions?page=-1"),
                Arguments.of(A1, "/transactions?size=0"),
                Arguments.of(A1, "/transactions?size=-1"),
                Arguments.of(A1, "/transactions?page=1.2"),
                Arguments.of(A1, "/transactions?size=abc"),
                Arguments.of(A1, "/transactions?page=9223372036854775807"));
    }

    @Test
    void 인증하지_않은_금융정보_조회는_401이다() throws Exception {
        mvc.perform(get("/api/v1/accounts/" + A1))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/accounts/" + A1 + "/transactions"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 큰_정확한_금액도_부동소수점으로_변환하지_않고_JSON에_반환한다() throws Exception {
        jdbc.update("UPDATE account SET balance = 9007199254740993.25 WHERE id = ?", UUID.fromString(A1));
        read("customer-a", "CUSTOMER", A1, "")
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"balance\":9007199254740993.25")));
    }

    private ResultActions read(String sub, String type, String account, String suffix) throws Exception {
        return mvc.perform(get("/api/v1/accounts/" + account + suffix)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + JwtTestTokens.token(sub, type)));
    }

    private void grantTransactionRead(String account) {
        jdbc.update("INSERT INTO access_grant VALUES (?, ?, '20000000-0000-4000-8000-000000000002', "
                        + "'TRANSACTION_READ', '10000000-0000-4000-8000-000000000001', now(), NULL)",
                UUID.randomUUID(),
                UUID.fromString(account));
    }
}
