package lab.financialapi.detection;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

import lab.financialapi.identity.PrincipalType;
import lab.financialapi.persistence.PostgresIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = {
        "detection.window-seconds=30",
        "detection.cooldown-seconds=30",
        "detection.poll-millis=2000"})
@Sql(scripts = {"/fixtures/clear-financial-data.sql", "/fixtures/financial-data.sql"})
@Sql(scripts = "/fixtures/clear-financial-data.sql", executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
class DetectionTimingIntegrationTest extends PostgresIntegrationSupport {
    private static final UUID SUBJECT = UUID.fromString("20000000-0000-4000-8000-000000000002");
    @Autowired DetectionMapper mapper;
    @Autowired DetectionService detection;
    @Autowired JdbcTemplate jdbc;

    @Test
    void 단축_집계창도_시작제외_끝포함이고_알림의_창도_동일하다() {
        var end = mapper.currentTime();
        events(end, 4);
        events(end.minusSeconds(30), 1);
        assertThat(mapper.candidatesInWindow(end, 30)).isEmpty();
        events(end, 1);
        events(end.plusSeconds(1), 1);
        assertThat(mapper.candidatesInWindow(end, 30).getFirst().violationCount()).isEqualTo(5);
        assertThat(detection.detect()).isEqualTo(1);
        var start = jdbc.queryForObject("SELECT window_start FROM security_alert", OffsetDateTime.class);
        var finish = jdbc.queryForObject("SELECT window_end FROM security_alert", OffsetDateTime.class);
        assertThat(Duration.between(start, finish)).isEqualTo(Duration.ofSeconds(30));
        assertThat(detection.detect()).isZero();
    }

    @Test
    void 단축_억제도_만료전_집계는_거부하고_만료경계부터_재선점한다() {
        var end = mapper.currentTime();
        assertThat(mapper.claimAfterCooldown("R-01", PrincipalType.PARTNER, SUBJECT, end, 30)).isPresent();
        var previous = end.minusSeconds(30);
        jdbc.update("UPDATE detection_state SET last_alert_at=?,last_window_end=?", previous, previous);
        assertThat(mapper.claimAfterCooldown("R-01", PrincipalType.PARTNER, SUBJECT, end.minusNanos(1000), 30)).isEmpty();
        assertThat(mapper.claimAfterCooldown("R-01", PrincipalType.PARTNER, SUBJECT, end, 30)).isPresent();
    }

    private void events(OffsetDateTime at, int count) {
        for (int i = 0; i < count; i++) {
            jdbc.update("INSERT INTO audit_event (id,occurred_at,principal_type,principal_id,action,resource_ref,result,reason_code,request_id) "
                            + "VALUES (?,?,'PARTNER',?,'ACCOUNT_READ',?,'DENIED','OBJECT_ACCESS_DENIED',?)",
                    UUID.randomUUID(), at, SUBJECT, "test:account:" + i % 3, UUID.randomUUID().toString());
        }
    }
}
