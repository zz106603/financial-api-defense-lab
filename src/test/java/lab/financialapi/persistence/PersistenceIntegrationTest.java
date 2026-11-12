package lab.financialapi.persistence;

import java.time.OffsetDateTime;
import java.util.UUID;

import lab.financialapi.account.AccountMapper;
import lab.financialapi.audit.AuditEvent;
import lab.financialapi.audit.AuditEventMapper;
import lab.financialapi.authorization.AccessGrant;
import lab.financialapi.authorization.AccessGrantMapper;
import lab.financialapi.authorization.Permission;
import lab.financialapi.detection.SecurityAlert;
import lab.financialapi.detection.SecurityAlertMapper;
import lab.financialapi.identity.CustomerMapper;
import lab.financialapi.identity.PartnerMapper;
import lab.financialapi.identity.PrincipalType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
@Sql("/fixtures/financial-data.sql")
class PersistenceIntegrationTest extends PostgresIntegrationSupport {
    static final UUID CUSTOMER_A = UUID.fromString("10000000-0000-4000-8000-000000000001");
    static final UUID CUSTOMER_B = UUID.fromString("10000000-0000-4000-8000-000000000002");
    static final UUID PARTNER_X = UUID.fromString("20000000-0000-4000-8000-000000000001");
    static final UUID PARTNER_Y = UUID.fromString("20000000-0000-4000-8000-000000000002");
    static final UUID ACCOUNT_A1 = UUID.fromString("30000000-0000-4000-8000-000000000001");
    static final UUID ACCOUNT_A2 = UUID.fromString("30000000-0000-4000-8000-000000000002");
    static final UUID ACCOUNT_B1 = UUID.fromString("30000000-0000-4000-8000-000000000003");
    static final UUID INITIAL_GRANT = UUID.fromString("50000000-0000-4000-8000-000000000001");
    static final OffsetDateTime TIME = OffsetDateTime.parse("2026-11-12T00:00:00Z");

    @Autowired CustomerMapper customers;
    @Autowired PartnerMapper partners;
    @Autowired AccountMapper accounts;
    @Autowired AccessGrantMapper grants;
    @Autowired AuditEventMapper audits;
    @Autowired SecurityAlertMapper alerts;
    @Autowired JdbcTemplate jdbc;

    @Test
    void 고객과_제휴사는_각자의_sub로만_식별된다() {
        var customer = customers.findByAuthSubject("customer-a").orElseThrow();
        assertThat(customer.id()).isEqualTo(CUSTOMER_A);
        assertThat(customer.name()).isEqualTo("고객 A");
        assertThat(customer.createdAt().toInstant()).isEqualTo(OffsetDateTime.parse("2026-11-11T00:00:00Z").toInstant());
        assertThat(partners.findByAuthSubject("partner-x").orElseThrow().id()).isEqualTo(PARTNER_X);
        assertThat(customers.findByAuthSubject("partner-x")).isEmpty();
        assertThat(partners.findByAuthSubject("customer-a")).isEmpty();
        assertThat(customers.findByAuthSubject("' OR '1'='1")).isEmpty();
        assertThat(customers.findByAuthSubject(null)).isEmpty();
    }

    @Test
    void 고객은_본인_계좌만_조회하고_금액은_정확히_매핑된다() {
        var account = accounts.findForCustomer(ACCOUNT_A1, CUSTOMER_A).orElseThrow();
        assertThat(account.id()).isEqualTo(ACCOUNT_A1);
        assertThat(account.customerId()).isEqualTo(CUSTOMER_A);
        assertThat(account.balance()).isEqualByComparingTo("1500000.25");
        assertThat(account.maskedAccountNumber()).isEqualTo("100-***-001");
        assertThat(accounts.findForCustomer(ACCOUNT_A1, CUSTOMER_B)).isEmpty();
        assertThat(accounts.findForCustomer(ACCOUNT_B1, CUSTOMER_A)).isEmpty();
        assertThat(accounts.findForCustomer(ACCOUNT_A1, null)).isEmpty();
        assertThat(accounts.findForCustomer(UUID.randomUUID(), CUSTOMER_A)).isEmpty();
    }

    @Test
    void 제휴사는_위임받은_계좌의_기본정보만_조회한다() {
        assertThat(accounts.findForPartner(ACCOUNT_A1, PARTNER_X)).isPresent();
        assertThat(accounts.findForPartner(ACCOUNT_A2, PARTNER_X)).isEmpty();
        assertThat(accounts.findForPartner(ACCOUNT_A1, PARTNER_Y)).isEmpty();
        assertThat(accounts.findForPartner(ACCOUNT_A1, null)).isEmpty();
        assertThat(accounts.findTransactionsForPartner(ACCOUNT_A1, PARTNER_X, 20, 0)).isEmpty();
    }

    @Test
    void 거래는_소유_계좌로_한정하고_동일_시각에도_페이지_정렬이_안정적이다() {
        var first = accounts.findTransactionsForCustomer(ACCOUNT_A1, CUSTOMER_A, 1, 0);
        var second = accounts.findTransactionsForCustomer(ACCOUNT_A1, CUSTOMER_A, 1, 1);
        assertThat(first).hasSize(1);
        assertThat(first.getFirst().id()).isEqualTo(UUID.fromString("40000000-0000-4000-8000-000000000002"));
        assertThat(first.getFirst().accountId()).isEqualTo(ACCOUNT_A1);
        assertThat(first.getFirst().amount()).isEqualByComparingTo("-100.25");
        assertThat(first.getFirst().occurredAt().toInstant()).isEqualTo(OffsetDateTime.parse("2026-11-11T01:30:00Z").toInstant());
        assertThat(second).hasSize(1);
        assertThat(second.getFirst().amount()).isEqualByComparingTo("50000.10");
        assertThat(accounts.findTransactionsForCustomer(ACCOUNT_A1, CUSTOMER_A, 1, 2)).isEmpty();
        assertThat(accounts.findTransactionsForCustomer(ACCOUNT_B1, CUSTOMER_A, 20, 0)).isEmpty();
        assertThat(accounts.findTransactionsForCustomer(ACCOUNT_A1, CUSTOMER_B, 20, 0)).isEmpty();
        assertThat(accounts.findTransactionsForCustomer(ACCOUNT_A1, null, 20, 0)).isEmpty();
        assertThat(accounts.findTransactionsForCustomer(ACCOUNT_A2, CUSTOMER_A, 20, 0)).isEmpty();
    }

    @Test
    void 거래_권한은_기본정보_권한을_포함하지_않고_다른_계좌나_제휴사로_확장되지_않는다() {
        assertThat(grants.insert(newGrant(ACCOUNT_A1, PARTNER_Y, Permission.TRANSACTION_READ, CUSTOMER_A))).isEqualTo(1);
        assertThat(accounts.findTransactionsForPartner(ACCOUNT_A1, PARTNER_Y, 20, 0)).hasSize(2);
        assertThat(accounts.findForPartner(ACCOUNT_A1, PARTNER_Y)).isEmpty();
        assertThat(accounts.findTransactionsForPartner(ACCOUNT_B1, PARTNER_Y, 20, 0)).isEmpty();
        assertThat(accounts.findTransactionsForPartner(ACCOUNT_A1, PARTNER_X, 20, 0)).isEmpty();
        assertThat(accounts.findTransactionsForPartner(ACCOUNT_A1, null, 20, 0)).isEmpty();
    }

    @Test
    void 권한_부여와_이력_조회는_계좌_소유자로_제한된다() {
        assertThat(grants.insert(newGrant(ACCOUNT_B1, PARTNER_X, Permission.ACCOUNT_READ, CUSTOMER_A))).isZero();
        assertThat(grants.findForOwner(INITIAL_GRANT, ACCOUNT_A1, CUSTOMER_B)).isEmpty();
        assertThat(grants.findForOwner(INITIAL_GRANT, ACCOUNT_A2, CUSTOMER_A)).isEmpty();
        var grant = grants.findForOwner(INITIAL_GRANT, ACCOUNT_A1, CUSTOMER_A).orElseThrow();
        assertThat(grant.permission()).isEqualTo(Permission.ACCOUNT_READ);
        assertThat(grant.revokedAt()).isNull();
        assertThat(grants.insert(newGrant(ACCOUNT_A2, PARTNER_Y, Permission.ACCOUNT_READ, CUSTOMER_A))).isEqualTo(1);
    }

    @Test
    void 회수는_계좌와_권한과_소유자와_활성_상태가_일치할_때만_수행된다() {
        assertThat(grants.revoke(INITIAL_GRANT, ACCOUNT_A1, CUSTOMER_B, TIME)).isZero();
        assertThat(grants.revoke(INITIAL_GRANT, ACCOUNT_A2, CUSTOMER_A, TIME)).isZero();
        assertThat(accounts.findForPartner(ACCOUNT_A1, PARTNER_X)).isPresent();
        assertThat(grants.revoke(INITIAL_GRANT, ACCOUNT_A1, CUSTOMER_A, TIME)).isEqualTo(1);
        assertThat(grants.revoke(INITIAL_GRANT, ACCOUNT_A1, CUSTOMER_A, TIME.plusHours(1))).isZero();
        assertThat(accounts.findForPartner(ACCOUNT_A1, PARTNER_X)).isEmpty();
        assertThat(grants.findForOwner(INITIAL_GRANT, ACCOUNT_A1, CUSTOMER_A).orElseThrow().revokedAt().toInstant())
                .isEqualTo(TIME.toInstant());
        assertThat(grants.insert(newGrant(ACCOUNT_A1, PARTNER_X, Permission.ACCOUNT_READ, CUSTOMER_A))).isEqualTo(1);
        assertThat(accounts.findForPartner(ACCOUNT_A1, PARTNER_X)).isPresent();
    }

    @Test
    void 거래_권한_회수는_거래만_거부하고_독립적인_기본정보_권한은_유지한다() {
        var grant = newGrant(ACCOUNT_A1, PARTNER_X, Permission.TRANSACTION_READ, CUSTOMER_A);
        grants.insert(grant);
        assertThat(accounts.findTransactionsForPartner(ACCOUNT_A1, PARTNER_X, 20, 0)).hasSize(2);
        assertThat(grants.revoke(grant.id(), ACCOUNT_A1, CUSTOMER_A, TIME)).isEqualTo(1);
        assertThat(accounts.findTransactionsForPartner(ACCOUNT_A1, PARTNER_X, 20, 0)).isEmpty();
        assertThat(accounts.findForPartner(ACCOUNT_A1, PARTNER_X)).isPresent();
    }

    @Test
    void 회수_시각이_없으면_권한을_변경하지_않는다() {
        assertThat(grants.revoke(INITIAL_GRANT, ACCOUNT_A1, CUSTOMER_A, null)).isZero();
        assertThat(accounts.findForPartner(ACCOUNT_A1, PARTNER_X)).isPresent();
    }

    @Test
    void 감사와_탐지_모델이_UUID와_시각과_가명_참조를_저장한다() {
        UUID eventId = UUID.randomUUID();
        assertThat(audits.insert(new AuditEvent(eventId, TIME, PrincipalType.PARTNER, PARTNER_X,
                "ACCOUNT_READ", "pseudonym-test-only", "DENIED", "OBJECT_ACCESS_DENIED", "req-test"))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT resource_ref FROM audit_event WHERE id = ?", String.class, eventId))
                .isEqualTo("pseudonym-test-only");
        assertThat(audits.insert(new AuditEvent(UUID.randomUUID(), TIME, PrincipalType.ANONYMOUS, null,
                "ACCOUNT_READ", null, "DENIED", "UNAUTHENTICATED", "req-anonymous"))).isEqualTo(1);
        UUID alertId = UUID.randomUUID();
        assertThat(alerts.insert(new SecurityAlert(alertId, "R-01", PrincipalType.PARTNER, PARTNER_X,
                TIME.minusMinutes(5), TIME, 5, 3, TIME))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT violation_count FROM security_alert WHERE id = ?", Integer.class, alertId))
                .isEqualTo(5);
    }

    static AccessGrant newGrant(UUID accountId, UUID partnerId, Permission permission, UUID grantedBy) {
        return new AccessGrant(UUID.randomUUID(), accountId, partnerId, permission, grantedBy, TIME, null);
    }
}
