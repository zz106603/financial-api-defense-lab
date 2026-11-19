package lab.financialapi.authorization;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.jayway.jsonpath.JsonPath;
import lab.financialapi.identity.JwtTestTokens;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(AccessGrantConcurrencyIntegrationTest.GateConfiguration.class)
class AccessGrantConcurrencyIntegrationTest extends GrantApiSupport {
    @Autowired GrantRaceGate gate;

    @TestConfiguration(proxyBeanMethods = false)
    static class GateConfiguration {
        @Bean
        GrantRaceGate grantRaceGate(JdbcTemplate jdbc) {
            return new GrantRaceGate(jdbc);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 동시_부여는_활성_한건이며_선행_감사_롤백이면_대기_요청이_성공한다(boolean failFirstAudit) throws Exception {
        gate.arm(".insertActive", ".insertActive");
        try (var executor = Executors.newFixedThreadPool(2)) {
            try {
                var first = executor.submit(named("first", () -> grantStatus()));
                gate.awaitFirst();
                if (failFirstAudit) {
                    failAuditForFirstConnection();
                }
                var second = executor.submit(named("second", () -> grantStatus()));
                assertSecondBlockedByFirst();
                assertThat(count("SELECT count(*) FROM access_grant WHERE partner_id = '" + Y + "'"))
                        .as("다른 연결에는 아직 미커밋 부여가 보이지 않는다").isZero();
                gate.release();
                assertThat(first.get(15, TimeUnit.SECONDS)).isEqualTo(failFirstAudit ? 503 : 201);
                assertThat(second.get(15, TimeUnit.SECONDS)).isEqualTo(failFirstAudit ? 201 : 409);
            } finally {
                gate.release();
            }
        } finally {
            clearFailureTrigger();
        }
        assertThat(count("SELECT count(*) FROM access_grant WHERE partner_id = '" + Y + "' AND revoked_at IS NULL"))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit_event WHERE reason_code = 'GRANT_CREATED'"))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit_event WHERE reason_code = 'ACTIVE_GRANT_CONFLICT'"))
                .isEqualTo(failFirstAudit ? 0 : 1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 동시_회수는_실제_회수가_한번이며_선행_감사_롤백이면_대기_요청이_회수한다(boolean failFirstAudit) throws Exception {
        gate.arm(".revoke", ".revoke");
        try (var executor = Executors.newFixedThreadPool(2)) {
            try {
                var first = executor.submit(named("first", () -> revokeStatus()));
                gate.awaitFirst();
                if (failFirstAudit) {
                    failAuditForFirstConnection();
                }
                var second = executor.submit(named("second", () -> revokeStatus()));
                assertSecondBlockedByFirst();
                assertThat(revokedAt(INITIAL)).isNull();
                gate.release();
                assertThat(first.get(15, TimeUnit.SECONDS)).isEqualTo(failFirstAudit ? 503 : 204);
                assertThat(second.get(15, TimeUnit.SECONDS)).isEqualTo(204);
            } finally {
                gate.release();
            }
        } finally {
            clearFailureTrigger();
        }
        assertThat(revokedAt(INITIAL)).isNotNull();
        assertThat(count("SELECT count(*) FROM audit_event WHERE reason_code = 'GRANT_REVOKED'"))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit_event WHERE reason_code = 'GRANT_ALREADY_REVOKED'"))
                .isEqualTo(failFirstAudit ? 0 : 1);
        Object timestamp = revokedAt(INITIAL);
        revoke("customer-a", "CUSTOMER", A1, INITIAL).andExpect(status().isNoContent());
        assertThat(revokedAt(INITIAL)).isEqualTo(timestamp);
    }

    @Test
    void 회수_미커밋_동안_조회는_허용되며_성공_응답_후_같은_JWT의_두_조회는_거부된다() throws Exception {
        String transaction = JsonPath.read(grant("customer-a", "CUSTOMER", A1, body(X, "TRANSACTION_READ"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.grantId");
        String jwt = JwtTestTokens.token("partner-x", "PARTNER");
        for (String grant : new String[]{INITIAL, transaction}) {
            String suffix = grant.equals(INITIAL) ? "" : "/transactions";
            gate.arm(".revoke", ".unused");
            try (var executor = Executors.newSingleThreadExecutor()) {
                try {
                    var revoke = executor.submit(named("first", () -> revoke("customer-a", "CUSTOMER", A1, grant)
                            .andReturn().getResponse().getStatus()));
                    gate.awaitFirst();
                    readWithJwt(jwt, suffix, 200);
                    gate.release();
                    assertThat(revoke.get(15, TimeUnit.SECONDS)).isEqualTo(204);
                    readWithJwt(jwt, suffix, 404);
                } finally {
                    gate.release();
                }
            }
        }
    }

    @Test
    void 회수_전에_시작한_진행중_조회는_완료되지만_커밋_후_새_조회는_거부된다() throws Exception {
        String jwt = JwtTestTokens.token("partner-x", "PARTNER");
        gate.arm(".readAccount", ".unused");
        try (var executor = Executors.newSingleThreadExecutor()) {
            try {
                var read = executor.submit(named("first", () -> readWithJwt(jwt, "", 200)));
                gate.awaitFirst();
                revoke("customer-a", "CUSTOMER", A1, INITIAL).andExpect(status().isNoContent());
                readWithJwt(jwt, "", 404);
                gate.release();
                assertThat(read.get(15, TimeUnit.SECONDS)).isEqualTo(200);
            } finally {
                gate.release();
            }
        }
    }

    @Test
    void 진행중_회수가_커밋되면_대기한_재부여는_새_ID로_생성되고_이전_ID는_영향을_주지_않는다() throws Exception {
        gate.arm(".revoke", ".insertActive");
        try (var executor = Executors.newFixedThreadPool(2)) {
            try {
                var first = executor.submit(named("first", () -> revokeStatus()));
                gate.awaitFirst();
                var second = executor.submit(named("second", () -> grant("customer-a", "CUSTOMER", A1, body(X, "ACCOUNT_READ"))
                        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()));
                assertSecondBlockedByFirst();
                gate.release();
                assertThat(first.get(15, TimeUnit.SECONDS)).isEqualTo(204);
                String replacement = JsonPath.read(second.get(15, TimeUnit.SECONDS), "$.grantId");
                assertThat(replacement).isNotEqualTo(INITIAL);
                revoke("customer-a", "CUSTOMER", A1, INITIAL).andExpect(status().isNoContent());
                assertThat(revokedAt(replacement)).isNull();
                assertThat(count("SELECT count(*) FROM access_grant WHERE revoked_at IS NULL")).isEqualTo(1);
                assertThat(count("SELECT count(*) FROM audit_event WHERE reason_code = 'GRANT_REVOKED'")).isEqualTo(1);
                assertThat(count("SELECT count(*) FROM audit_event WHERE reason_code = 'GRANT_CREATED'")).isEqualTo(1);
            } finally {
                gate.release();
            }
        }
    }

    private void assertSecondBlockedByFirst() throws Exception {
        gate.awaitSecond();
        assertThat(gate.secondPid).isNotEqualTo(gate.firstPid);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("SELECT ? = ANY(pg_blocking_pids(?))", Boolean.class,
                        gate.firstPid, gate.secondPid)).isTrue());
    }

    private int grantStatus() throws Exception {
        return grant("customer-a", "CUSTOMER", A1, body(Y, "ACCOUNT_READ"))
                .andReturn().getResponse().getStatus();
    }

    private int revokeStatus() throws Exception {
        return revoke("customer-a", "CUSTOMER", A1, INITIAL).andReturn().getResponse().getStatus();
    }

    private int readWithJwt(String jwt, String suffix, int expected) throws Exception {
        return mvc.perform(get("/api/v1/accounts/" + A1 + suffix)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt))
                .andExpect(status().is(expected)).andReturn().getResponse().getStatus();
    }

    private void failAuditForFirstConnection() {
        // DB가 실제 INSERT를 실패시킨다. 첫 연결에만 주입해 경쟁 요청은 정상 커밋할 수 있다.
        jdbc.execute("CREATE FUNCTION test_fail_race_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF pg_backend_pid() = " + gate.firstPid + " THEN RAISE EXCEPTION 'synthetic audit failure'; END IF; "
                + "RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER test_fail_race_audit BEFORE INSERT ON audit_event "
                + "FOR EACH ROW EXECUTE FUNCTION test_fail_race_audit()");
    }

    private void clearFailureTrigger() {
        jdbc.execute("DROP TRIGGER IF EXISTS test_fail_race_audit ON audit_event");
        jdbc.execute("DROP FUNCTION IF EXISTS test_fail_race_audit()");
    }

    private <T> Callable<T> named(String suffix, Callable<T> operation) {
        return () -> {
            String original = Thread.currentThread().getName();
            Thread.currentThread().setName("grant-race-" + suffix);
            try {
                return operation.call();
            } finally {
                Thread.currentThread().setName(original);
            }
        };
    }
}
