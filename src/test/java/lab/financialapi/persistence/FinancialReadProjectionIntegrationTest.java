package lab.financialapi.persistence;

import java.util.UUID;

import lab.financialapi.account.AccountMapper;
import lab.financialapi.identity.PrincipalType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import static lab.financialapi.persistence.PersistenceIntegrationTest.*;
import static org.assertj.core.api.Assertions.assertThat;

@Transactional
@Sql("/fixtures/financial-data.sql")
class FinancialReadProjectionIntegrationTest extends PostgresIntegrationSupport {
    @Autowired AccountMapper accounts;

    @Test
    void 계좌_조회는_허용된_행만_반환하고_거부와_미존재_사유를_내부에서_구분한다() {
        var allowed = accounts.readAccount(ACCOUNT_A1, CUSTOMER_A, PrincipalType.CUSTOMER);
        assertThat(allowed.accountId()).isEqualTo(ACCOUNT_A1);
        assertThat(allowed.balance()).isEqualByComparingTo("1500000.25");
        var denied = accounts.readAccount(ACCOUNT_A1, CUSTOMER_B, PrincipalType.CUSTOMER);
        assertThat(denied.accountExists()).isTrue();
        assertThat(denied.accountId()).isNull();
        assertThat(denied.balance()).isNull();
        var missing = accounts.readAccount(UUID.randomUUID(), CUSTOMER_A, PrincipalType.CUSTOMER);
        assertThat(missing.accountExists()).isFalse();
        assertThat(missing.accountId()).isNull();
        assertThat(accounts.readAccount(ACCOUNT_A1, PARTNER_X, PrincipalType.PARTNER).accountId()).isEqualTo(ACCOUNT_A1);
        assertThat(accounts.readAccount(ACCOUNT_A1, PARTNER_Y, PrincipalType.PARTNER).accountId()).isNull();
        assertThat(accounts.readAccount(ACCOUNT_A1, PARTNER_X, PrincipalType.ANONYMOUS).accountId()).isNull();
        assertThat(accounts.readAccount(ACCOUNT_A1, null, PrincipalType.CUSTOMER).accountId()).isNull();
    }

    @Test
    void 거래가_없거나_페이지를_벗어나도_허용_여부를_독립적으로_반환한다() {
        var empty = accounts.readTransactionPage(ACCOUNT_A2, CUSTOMER_A, PrincipalType.CUSTOMER, 2, 0);
        assertThat(empty).hasSize(1);
        assertThat(empty.getFirst().authorizedAccountId()).isEqualTo(ACCOUNT_A2);
        assertThat(empty.getFirst().transactionId()).isNull();
        var beyond = accounts.readTransactionPage(ACCOUNT_A1, CUSTOMER_A, PrincipalType.CUSTOMER, 2, 10);
        assertThat(beyond).hasSize(1);
        assertThat(beyond.getFirst().authorizedAccountId()).isEqualTo(ACCOUNT_A1);
        assertThat(beyond.getFirst().transactionId()).isNull();
        var denied = accounts.readTransactionPage(ACCOUNT_A2, CUSTOMER_B, PrincipalType.CUSTOMER, 2, 0);
        assertThat(denied.getFirst().accountExists()).isTrue();
        assertThat(denied.getFirst().authorizedAccountId()).isNull();
        var missing = accounts.readTransactionPage(UUID.randomUUID(), CUSTOMER_A, PrincipalType.CUSTOMER, 2, 0);
        assertThat(missing.getFirst().accountExists()).isFalse();
        assertThat(missing.getFirst().authorizedAccountId()).isNull();
    }

    @Test
    void 거래_조회는_권한을_분리하고_계좌별_정렬과_페이지를_유지한다() {
        var first = accounts.readTransactionPage(ACCOUNT_A1, CUSTOMER_A, PrincipalType.CUSTOMER, 1, 0);
        var second = accounts.readTransactionPage(ACCOUNT_A1, CUSTOMER_A, PrincipalType.CUSTOMER, 1, 1);
        assertThat(first).hasSize(1);
        assertThat(first.getFirst().transactionId()).isEqualTo(UUID.fromString("40000000-0000-4000-8000-000000000002"));
        assertThat(second.getFirst().transactionId()).isEqualTo(UUID.fromString("40000000-0000-4000-8000-000000000001"));
        var denied = accounts.readTransactionPage(ACCOUNT_A1, PARTNER_X, PrincipalType.PARTNER, 2, 0);
        assertThat(denied.getFirst().authorizedAccountId()).isNull();
        assertThat(denied.getFirst().amount()).isNull();
        assertThat(accounts.readTransactionPage(ACCOUNT_B1, CUSTOMER_A, PrincipalType.CUSTOMER, 2, 0)
                .getFirst().authorizedAccountId()).isNull();
    }
}
