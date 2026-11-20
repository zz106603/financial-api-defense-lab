package lab.financialapi.detection;

import java.sql.Connection;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import lab.financialapi.audit.ResourceReferenceGenerator;
import lab.financialapi.identity.PrincipalType;
import lab.financialapi.persistence.PostgresIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@Sql(scripts = {"/fixtures/clear-financial-data.sql", "/fixtures/financial-data.sql"})
@Sql(scripts = "/fixtures/clear-financial-data.sql", executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
class DetectionIntegrationTest extends PostgresIntegrationSupport {
    static final UUID SUBJECT = UUID.fromString("10000000-0000-4000-8000-000000000001");
    static final OffsetDateTime T = OffsetDateTime.parse("2026-11-19T01:10:00Z");
    @Autowired DetectionMapper mapper;
    @Autowired DetectionService detection;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired ResourceReferenceGenerator references;

    @Test
    void 창_시작은_제외하고_끝은_포함하며_미래_이벤트는_집계하지_않는다() {
        violations(4, 3, T.minusMinutes(1));
        event("CUSTOMER", SUBJECT, T.minusMinutes(5), 0, "DENIED", "OBJECT_ACCESS_DENIED", "ACCOUNT_READ");
        assertThat(mapper.candidatesInWindow(T, 300)).isEmpty();
        event("CUSTOMER", SUBJECT, T, 1, "DENIED", "OBJECT_ACCESS_DENIED", "TRANSACTION_READ");
        event("CUSTOMER", SUBJECT, T.plusNanos(1000), 2, "DENIED", "OBJECT_ACCESS_DENIED", "ACCOUNT_READ");
        var result = mapper.candidatesInWindow(T, 300);
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().violationCount()).isEqualTo(5);
        assertThat(result.getFirst().distinctResourceCount()).isEqualTo(3);
    }

    @Test
    void 인증_입력_미존재_역할_중복_허용_익명_대상없는_이벤트는_제외한다() {
        violations(4, 3, T);
        for (String reason : new String[]{"AUTHENTICATION_FAILED", "INVALID_REQUEST", "RESOURCE_NOT_FOUND",
                "PRINCIPAL_TYPE_DENIED", "ACTIVE_GRANT_CONFLICT"}) {
            event("CUSTOMER", SUBJECT, T, 0, "DENIED", reason, "ACCOUNT_READ");
        }
        event("CUSTOMER", SUBJECT, T, 0, "ALLOWED", "OBJECT_ACCESS_DENIED", "ACCOUNT_READ");
        event("ANONYMOUS", null, T, 0, "DENIED", "OBJECT_ACCESS_DENIED", "ACCOUNT_READ");
        event("CUSTOMER", SUBJECT, T, -1, "DENIED", "OBJECT_ACCESS_DENIED", "ACCOUNT_READ");
        event("CUSTOMER", SUBJECT, T, 0, "DENIED", "OBJECT_ACCESS_DENIED", "UNSUPPORTED_ACTION");
        assertThat(mapper.candidatesInWindow(T, 300)).isEmpty();
        event("CUSTOMER", SUBJECT, T, 0, "DENIED", "OBJECT_ACCESS_DENIED", "GRANT_REVOKE");
        assertThat(mapper.candidatesInWindow(T, 300).getFirst().violationCount()).isEqualTo(5);
    }

    @Test
    void 횟수와_서로다른_계좌_조건을_모두_충족해야_한다() {
        violations(5, 2, T);
        assertThat(mapper.candidatesInWindow(T, 300)).isEmpty();
        event("CUSTOMER", SUBJECT, T, 2, "DENIED", "OBJECT_ACCESS_DENIED", "GRANT_CREATE");
        assertThat(mapper.candidatesInWindow(T, 300).getFirst().distinctResourceCount()).isEqualTo(3);
    }

    @Test
    void 같은_UUID도_주체_유형이_다르면_위반_횟수를_합치지_않는다() {
        violations(4, 3, T);
        for (int i = 0; i < 4; i++) {
            event("PARTNER", SUBJECT, T, i % 3, "DENIED", "OBJECT_ACCESS_DENIED", "ACCOUNT_READ");
        }
        assertThat(mapper.candidatesInWindow(T, 300)).isEmpty();
        event("PARTNER", SUBJECT, T, 0, "DENIED", "OBJECT_ACCESS_DENIED", "ACCOUNT_READ");
        assertThat(mapper.candidatesInWindow(T, 300)).hasSize(1);
        assertThat(mapper.candidatesInWindow(T, 300).getFirst().principalType()).isEqualTo(PrincipalType.PARTNER);
    }

    @Test
    void 임계값을_충족하면_알림을_생성하고_반복_실행은_5분간_억제한다() {
        violations(5, 3, mapper.currentTime().minusSeconds(1));
        assertThat(detection.detect()).isEqualTo(1);
        assertThat(detection.detect()).isZero();
        var alert = jdbc.queryForMap("SELECT * FROM security_alert");
        assertThat(alert.get("rule_id")).isEqualTo("R-01");
        assertThat(alert.get("violation_count")).isEqualTo(5);
        assertThat(alert.get("distinct_resource_count")).isEqualTo(3);
        OffsetDateTime start = jdbc.queryForObject("SELECT window_start FROM security_alert", OffsetDateTime.class);
        OffsetDateTime end = jdbc.queryForObject("SELECT window_end FROM security_alert", OffsetDateTime.class);
        assertThat(Duration.between(start, end)).isEqualTo(Duration.ofMinutes(5));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM detection_state", Integer.class)).isEqualTo(1);
    }

    @Test
    void 억제_5분_후에도_최신_조건이_미충족이면_없고_재충족하면_재알림한다() {
        violations(5, 3, mapper.currentTime().minusSeconds(1));
        assertThat(detection.detect()).isEqualTo(1);
        jdbc.update("UPDATE detection_state SET last_alert_at = clock_timestamp() - interval '5 minutes 1 second', "
                + "last_window_end = clock_timestamp() - interval '5 minutes 1 second'");
        jdbc.update("UPDATE audit_event SET occurred_at = clock_timestamp() - interval '6 minutes'");
        assertThat(detection.detect()).isZero();
        violations(5, 3, mapper.currentTime().minusSeconds(1));
        assertThat(detection.detect()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM security_alert", Integer.class)).isEqualTo(2);
    }

    @Test
    void 억제기간이_지나도_동일하거나_과거_집계창은_다시_사용하지_않는다() {
        assertThat(mapper.claimAfterCooldown("R-01", PrincipalType.CUSTOMER, SUBJECT, T, 300)).isPresent();
        jdbc.update("UPDATE detection_state SET last_alert_at = ?", T.minusMinutes(6));
        assertThat(mapper.claimAfterCooldown("R-01", PrincipalType.CUSTOMER, SUBJECT, T, 300)).isEmpty();
        assertThat(mapper.claimAfterCooldown("R-01", PrincipalType.CUSTOMER, SUBJECT, T.minusSeconds(30), 300)).isEmpty();
        assertThat(mapper.claimAfterCooldown("R-01", PrincipalType.CUSTOMER, SUBJECT, T.plusSeconds(30), 300)).isPresent();
    }

    @Test
    void 선점_시점이_만료후라도_만료전에_집계한_창은_재알림에_쓰지_않는다() {
        // 실시간 대기 없이 DB시각과 집계T를 분리해 잠금 대기 직전/직후를 재현한다.
        jdbc.update("INSERT INTO detection_state VALUES ('R-01','CUSTOMER',?,?,?)",
                SUBJECT, T.minusMinutes(5).plusSeconds(1), T.minusMinutes(5).plusSeconds(1));
        assertThat(mapper.claimAfterCooldown("R-01", PrincipalType.CUSTOMER, SUBJECT, T, 300)).isEmpty();
        assertThat(mapper.claimAfterCooldown("R-01", PrincipalType.CUSTOMER, SUBJECT, T.plusSeconds(1), 300)).isPresent();
    }

    @Test
    void 알림_INSERT_실패는_억제_상태도_롤백해_다음_실행이_재시도한다() {
        violations(5, 3, mapper.currentTime().minusSeconds(1));
        jdbc.execute("ALTER TABLE security_alert RENAME TO unavailable_alert");
        try {
            assertThatThrownBy(detection::detect).isInstanceOf(RuntimeException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM detection_state", Integer.class)).isZero();
        } finally {
            jdbc.execute("ALTER TABLE unavailable_alert RENAME TO security_alert");
        }
        assertThat(detection.detect()).isEqualTo(1);
    }

    @Test
    void 알림_커밋_실패도_억제_상태와_알림을_함께_롤백한다() {
        violations(5, 3, mapper.currentTime().minusSeconds(1));
        jdbc.execute("CREATE FUNCTION test_fail_alert() RETURNS trigger LANGUAGE plpgsql AS $$ "
                + "BEGIN RAISE EXCEPTION 'synthetic alert commit failure'; RETURN NEW; END $$");
        jdbc.execute("CREATE CONSTRAINT TRIGGER test_fail_alert AFTER INSERT ON security_alert "
                + "DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION test_fail_alert()");
        try {
            assertThatThrownBy(detection::detect).isInstanceOf(RuntimeException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM detection_state", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM security_alert", Integer.class)).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER test_fail_alert ON security_alert");
            jdbc.execute("DROP FUNCTION test_fail_alert()");
        }
        assertThat(detection.detect()).isEqualTo(1);
    }

    @Test
    void 두_실행이_같은_상태_행에서_실제로_대기해도_한_알림만_커밋한다() throws Exception {
        violations(5, 3, mapper.currentTime().minusSeconds(1));
        try (Connection connection = dataSource.getConnection(); var executor = Executors.newFixedThreadPool(2)) {
            connection.setAutoCommit(false);
            try {
                try (var insert = connection.prepareStatement("INSERT INTO detection_state VALUES "
                        + "('R-01','CUSTOMER',?,clock_timestamp()-interval '6 minutes',clock_timestamp()-interval '6 minutes')")) {
                    insert.setObject(1, SUBJECT);
                    insert.executeUpdate();
                }
                var ready = new CountDownLatch(2);
                var first = executor.submit(() -> { ready.countDown(); return detection.detect(); });
                var second = executor.submit(() -> { ready.countDown(); return detection.detect(); });
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
                await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' "
                                + "AND query LIKE '%INSERT INTO detection_state%'", Integer.class)).isEqualTo(2));
                connection.commit();
                assertThat(first.get(15, TimeUnit.SECONDS) + second.get(15, TimeUnit.SECONDS)).isEqualTo(1);
            } finally {
                connection.rollback();
            }
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM security_alert", Integer.class)).isEqualTo(1);
    }

    private void violations(int count, int distinct, OffsetDateTime time) {
        for (int i = 0; i < count; i++) {
            event("CUSTOMER", SUBJECT, time, i % distinct, "DENIED", "OBJECT_ACCESS_DENIED", "ACCOUNT_READ");
        }
    }

    private void event(
            String type, UUID subject, OffsetDateTime time, int resource,
            String result, String reason, String action) {
        String ref = resource < 0 ? null : references.accountReference(new UUID(0, resource + 1));
        jdbc.update("INSERT INTO audit_event (id, occurred_at, principal_type, principal_id, action, resource_ref, result, reason_code, request_id) VALUES (?,?,?,?,?,?,?,?,?)", UUID.randomUUID(), time,
                type, subject, action, ref, result, reason, UUID.randomUUID().toString());
    }
}
