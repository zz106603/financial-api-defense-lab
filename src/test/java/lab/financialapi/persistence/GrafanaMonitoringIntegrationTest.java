package lab.financialapi.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.testcontainers.utility.MountableFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Sql(scripts = {"/fixtures/clear-financial-data.sql", "/fixtures/financial-data.sql"})
@Sql(scripts = "/fixtures/clear-financial-data.sql", executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
class GrafanaMonitoringIntegrationTest extends PostgresIntegrationSupport {
    private static final String PASSWORD = "synthetic-grafana-test-password";
    private static final String SUBJECT = "20000000-0000-4000-8000-000000000002";
    private static final String T = "2026-11-24T04:00:00Z";
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void applyReaderScript() throws Exception {
        POSTGRES.copyFileToContainer(MountableFile.forHostPath(
                Path.of("monitoring/grafana-reader.sql").toAbsolutePath()), "/tmp/grafana-reader.sql");
        var result = POSTGRES.execInContainer("env", "GRAFANA_DB_PASSWORD=" + PASSWORD,
                "psql", "-X", "-v", "ON_ERROR_STOP=1", "-U", POSTGRES.getUsername(),
                "-d", POSTGRES.getDatabaseName(), "-f", "/tmp/grafana-reader.sql");
        assertThat(result.getExitCode()).as("reader provisioning exit code").isZero();
    }

    @Test
    void 읽기전용_해제후에도_금융테이블_쓰기_DDL_역할승격은_거부한다() throws Exception {
        try (var connection = reader(); var statement = connection.createStatement()) {
            try (var result = statement.executeQuery("SHOW default_transaction_read_only")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo("on");
            }
            statement.execute("SET default_transaction_read_only=off");
            for (String table : List.of("audit_event", "security_alert", "detection_state")) {
                assertThat(statement.executeQuery("SELECT count(*) FROM " + table).next()).isTrue();
                denied(connection, "DELETE FROM " + table);
                denied(connection, "INSERT INTO " + table + " DEFAULT VALUES");
                denied(connection, "UPDATE " + table + " SET principal_type='CUSTOMER'");
                denied(connection, "ALTER TABLE " + table + " ADD COLUMN unauthorized integer");
            }
            for (String table : List.of("customer", "partner", "account", "transaction_record", "access_grant")) {
                denied(connection, "SELECT * FROM " + table);
            }
            denied(connection, "CREATE TABLE public.unauthorized(id integer)");
            denied(connection, "SET ROLE " + POSTGRES.getUsername());
            try (var result = statement.executeQuery("SHOW statement_timeout")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo("5s");
            }
        }
    }

    @Test
    void PUBLIC으로_금융정보가_노출된_DB에서는_계정설정을_중단한다() throws Exception {
        jdbc.execute("GRANT SELECT ON account TO PUBLIC");
        try {
            var result = POSTGRES.execInContainer("env", "GRAFANA_DB_PASSWORD=" + PASSWORD,
                    "psql", "-X", "-U", POSTGRES.getUsername(), "-d", POSTGRES.getDatabaseName(),
                    "-f", "/tmp/grafana-reader.sql");
            assertThat(result.getExitCode()).isNotZero();
            assertThat(result.getStderr()).contains("excess privileges");
        } finally {
            jdbc.execute("REVOKE SELECT ON account FROM PUBLIC");
        }
    }

    @Test
    void 모든_패널_SQL이_전용계정으로_실행되고_5분과_정책창을_구분한다() throws Exception {
        event("2026-11-24T03:55:00Z", 0, "DENIED", "OBJECT_ACCESS_DENIED");
        event("2026-11-24T03:59:29Z", 0, "DENIED", "OBJECT_ACCESS_DENIED");
        event("2026-11-24T03:59:30Z", 0, "DENIED", "OBJECT_ACCESS_DENIED");
        for (int i = 0; i < 5; i++) {
            event(T, i % 3, "DENIED", "OBJECT_ACCESS_DENIED");
        }
        event(T, 0, "ALLOWED", "ACCESS_ALLOWED");
        event(T, 0, "DENIED", "INVALID_REQUEST");
        event("2026-11-24T04:00:01Z", 0, "DENIED", "OBJECT_ACCESS_DENIED");
        try (var connection = reader(); var statement = connection.createStatement()) {
            for (String sql : panelQueries()) {
                try (var result = statement.executeQuery(render(sql))) {
                    assertThat(result.getMetaData().getColumnCount()).isGreaterThan(0);
                }
            }
            try (var result = statement.executeQuery(render(panelQuery(4)))) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt("violation_count")).isEqualTo(7);
                assertThat(result.getInt("distinct_resource_count")).isEqualTo(3);
            }
            try (var result = statement.executeQuery(render(panelQuery(5)))) {
                assertThat(result.next()).isTrue();
                assertThat(result.getBoolean("threshold_met")).isTrue();
                assertThat(result.getInt("violation_count")).isEqualTo(5);
                assertThat(result.getString("suppression_status")).isEqualTo("NO_PREVIOUS_ALERT");
            }
        }
    }

    @Test
    void 억제_중과_만료경계를_구분하고_조회는_상태를_변경하지_않는다() throws Exception {
        for (int i = 0; i < 5; i++) {
            event(T, i % 3, "DENIED", "OBJECT_ACCESS_DENIED");
        }
        jdbc.update("INSERT INTO detection_state VALUES ('R-01','PARTNER',?::uuid,?::timestamptz,?::timestamptz)",
                SUBJECT, "2026-11-24T03:59:31Z", "2026-11-24T03:59:31Z");
        try (var connection = reader(); var statement = connection.createStatement()) {
            try (var result = statement.executeQuery(render(panelQuery(5)))) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("suppression_status")).isEqualTo("SUPPRESSED");
                assertThat(result.getInt("remaining_seconds")).isEqualTo(1);
            }
            jdbc.update("UPDATE detection_state SET last_alert_at='2026-11-24T03:59:30Z',last_window_end='2026-11-24T03:59:30Z'");
            try (var result = statement.executeQuery(render(panelQuery(5)))) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("suppression_status")).isEqualTo("ELIGIBLE_NOT_A_GUARANTEE");
                assertThat(result.getInt("remaining_seconds")).isZero();
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM security_alert", Integer.class)).isZero();
        }
    }

    @Test
    void 대시보드_변수는_SQL문자열로_인용하고_비밀번호는_설정파일에_넣지_않는다() throws Exception {
        var json = Files.readString(Path.of("monitoring/dashboards/security.json"));
        assertThat(json).contains("${window_seconds:sqlstring}", "${cooldown_seconds:sqlstring}")
                .doesNotContain(":raw}", "password");
        assertThat(Files.readString(Path.of("monitoring/provisioning/datasources/postgres.yaml")))
                .contains("password: $GRAFANA_DB_PASSWORD", "editable: false");
    }

    private Connection reader() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), "grafana_reader", PASSWORD);
    }

    private void denied(Connection connection, String sql) {
        assertThatThrownBy(() -> {
            try (var statement = connection.createStatement()) {
                statement.execute(sql);
            }
        }).isInstanceOfSatisfying(SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("42501"));
    }

    private void event(String at, int resource, String result, String reason) {
        jdbc.update("INSERT INTO audit_event VALUES (gen_random_uuid(),?::timestamptz,'PARTNER',?::uuid,"
                        + "'ACCOUNT_READ',?,?,?,gen_random_uuid()::text)",
                at, SUBJECT, "test:account:" + resource, result, reason);
    }

    private String render(String sql) {
        return sql.replace("${window_seconds:sqlstring}", "'30'")
                .replace("${cooldown_seconds:sqlstring}", "'30'")
                .replace("${__from}", "1795478400000").replace("${__to}", "1795579200000")
                .replace("statement_timestamp()", "TIMESTAMPTZ '" + T + "'");
    }

    @SuppressWarnings("unchecked")
    private List<String> panelQueries() throws Exception {
        return JsonPath.read(Files.readString(Path.of("monitoring/dashboards/security.json")),
                "$.panels[*].targets[*].rawSql");
    }

    private String panelQuery(int id) throws Exception {
        List<Map<String, Object>> panels = JsonPath.read(
                Files.readString(Path.of("monitoring/dashboards/security.json")), "$.panels[?(@.id == " + id + ")]");
        @SuppressWarnings("unchecked")
        var targets = (List<Map<String, Object>>) panels.getFirst().get("targets");
        return (String) targets.getFirst().get("rawSql");
    }
}
