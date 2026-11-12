package lab.financialapi.persistence;

import java.sql.SQLException;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Transactional
@Sql("/fixtures/financial-data.sql")
class SchemaIntegrationTest extends PostgresIntegrationSupport {
    @Autowired JdbcTemplate jdbc;

    @Test
    void 일곱_업무_테이블이_생성된다() {
        for (String table : new String[]{"customer", "partner", "account", "transaction_record",
                "access_grant", "audit_event", "security_alert"}) {
            assertThat(jdbc.queryForObject("SELECT to_regclass(?)::text", String.class, table)).isEqualTo(table);
        }
    }

    @Test
    void 동일한_활성_권한은_중복될_수_없다() {
        assertViolation("INSERT INTO access_grant SELECT gen_random_uuid(), account_id, partner_id, "
                + "permission, granted_by, granted_at, NULL FROM access_grant", "23505");
    }

    @Test
    void 회수_후에는_새_권한을_부여하고_기존_이력을_보존한다() {
        jdbc.update("UPDATE access_grant SET revoked_at = granted_at + interval '1 hour'");
        jdbc.update("INSERT INTO access_grant SELECT gen_random_uuid(), account_id, partner_id, "
                + "permission, granted_by, granted_at + interval '2 hours', NULL FROM access_grant");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM access_grant", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM access_grant WHERE revoked_at IS NULL", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void 소유자가_아닌_고객을_부여자로_저장할_수_없다() {
        assertViolation("UPDATE access_grant SET granted_by = '10000000-0000-4000-8000-000000000002'", "23503");
    }

    @Test
    void 정의되지_않은_권한은_저장할_수_없다() {
        assertViolation("UPDATE access_grant SET permission = 'ALL'", "23514");
    }

    @Test
    void 부여보다_이른_회수_시각은_저장할_수_없다() {
        assertViolation("UPDATE access_grant SET revoked_at = granted_at - interval '1 second'", "23514");
    }

    @Test
    void 존재하지_않는_계좌의_거래는_저장할_수_없다() {
        assertViolation("UPDATE transaction_record SET account_id = '99999999-0000-4000-8000-000000000001'", "23503");
    }

    @Test
    void 필수_잔액은_null로_저장할_수_없다() {
        assertViolation("UPDATE account SET balance = NULL", "23502");
    }

    @Test
    void 익명_감사에는_검증되지_않은_주체_ID를_저장할_수_없다() {
        assertViolation("INSERT INTO audit_event VALUES (gen_random_uuid(), now(), 'ANONYMOUS', "
                + "gen_random_uuid(), 'ACCOUNT_READ', NULL, 'DENIED', 'UNAUTHENTICATED', 'req-test')", "23514");
    }

    @Test
    void 탐지_분석_종료가_시작보다_이를_수_없다() {
        assertViolation("INSERT INTO security_alert VALUES (gen_random_uuid(), 'R-01', 'CUSTOMER', "
                + "gen_random_uuid(), now(), now() - interval '1 second', 5, 3, now())", "23514");
    }

    private void assertViolation(String sql, String sqlState) {
        assertThatThrownBy(() -> jdbc.execute(sql))
                .rootCause().isInstanceOf(SQLException.class)
                .extracting(error -> ((SQLException) error).getSQLState()).isEqualTo(sqlState);
    }
}
