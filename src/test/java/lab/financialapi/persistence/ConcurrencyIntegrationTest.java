package lab.financialapi.persistence;

import java.sql.Connection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import lab.financialapi.account.AccountMapper;
import lab.financialapi.authorization.AccessGrantMapper;
import lab.financialapi.authorization.Permission;
import lab.financialapi.identity.PrincipalType;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static lab.financialapi.persistence.PersistenceIntegrationTest.*;
import static org.assertj.core.api.Assertions.assertThat;

@Sql(scripts = {"/fixtures/clear-financial-data.sql", "/fixtures/financial-data.sql"})
@Sql(scripts = "/fixtures/clear-financial-data.sql", executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
class ConcurrencyIntegrationTest extends PostgresIntegrationSupport {
    @Autowired AccessGrantMapper grants;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired SqlSessionFactory sessions;

    @Test
    void API용_투영도_별도_회수_커밋_후_동일_세션에서_계좌와_거래를_거부한다() {
        var transactionGrant = newGrant(ACCOUNT_A1, PARTNER_Y, Permission.TRANSACTION_READ, CUSTOMER_A);
        grants.insert(transactionGrant);
        try (var session = sessions.openSession(false)) {
            var mapper = session.getMapper(AccountMapper.class);
            assertThat(mapper.readAccount(ACCOUNT_A1, PARTNER_X, PrincipalType.PARTNER).accountId()).isEqualTo(ACCOUNT_A1);
            assertThat(mapper.readTransactionPage(ACCOUNT_A1, PARTNER_Y, PrincipalType.PARTNER, 2, 0)
                    .getFirst().authorizedAccountId()).isEqualTo(ACCOUNT_A1);
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                assertThat(grants.revoke(INITIAL_GRANT, ACCOUNT_A1, CUSTOMER_A, TIME)).isEqualTo(1);
                assertThat(grants.revoke(transactionGrant.id(), ACCOUNT_A1, CUSTOMER_A, TIME)).isEqualTo(1);
            });
            assertThat(mapper.readAccount(ACCOUNT_A1, PARTNER_X, PrincipalType.PARTNER).accountId()).isNull();
            var page = mapper.readTransactionPage(ACCOUNT_A1, PARTNER_Y, PrincipalType.PARTNER, 2, 0);
            assertThat(page.getFirst().authorizedAccountId()).isNull();
            assertThat(page.getFirst().amount()).isNull();
            session.rollback();
        }
    }

    @Test
    void 별도_트랜잭션의_회수_커밋_후에는_동일_세션의_새_조회도_거부된다() {
        // SESSION 캐시라면 두 번째 동일 조회가 DB를 거치지 않고 예전 결과를 반환한다.
        try (var session = sessions.openSession(false)) {
            assertThat(session.getConnection().getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
            var mapper = session.getMapper(AccountMapper.class);
            assertThat(mapper.findForPartner(ACCOUNT_A1, PARTNER_X)).isPresent();
            Integer changed = new TransactionTemplate(transactionManager).execute(status ->
                    grants.revoke(INITIAL_GRANT, ACCOUNT_A1, CUSTOMER_A, TIME));
            assertThat(changed).isEqualTo(1);
            assertThat(mapper.findForPartner(ACCOUNT_A1, PARTNER_X)).isEmpty();
            session.rollback();
        } catch (java.sql.SQLException error) {
            throw new AssertionError(error);
        }
    }

    @Test
    void 동시_권한_부여는_한_건만_커밋되고_중복은_거부된다() throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> concurrentGrant(ready, start));
            var second = executor.submit(() -> concurrentGrant(ready, start));
            try {
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                start.countDown();
            }
            assertThat(first.get(15, TimeUnit.SECONDS) + second.get(15, TimeUnit.SECONDS)).isEqualTo(1);
        }
        try (var session = sessions.openSession()) {
            var statement = session.getConnection().prepareStatement(
                    "SELECT count(*) FROM access_grant WHERE account_id = ? AND partner_id = ? "
                            + "AND permission = 'ACCOUNT_READ' AND revoked_at IS NULL");
            statement.setObject(1, ACCOUNT_A2);
            statement.setObject(2, PARTNER_Y);
            try (statement; var result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isEqualTo(1);
            }
        }
    }

    private int concurrentGrant(CountDownLatch ready, CountDownLatch start) {
        try {
            return new TransactionTemplate(transactionManager).execute(status -> {
                ready.countDown();
                try {
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new AssertionError("동시 부여 시작 신호 시간 초과");
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(error);
                }
                grants.insert(newGrant(ACCOUNT_A2, PARTNER_Y, Permission.ACCOUNT_READ, CUSTOMER_A));
                return 1;
            });
        } catch (DuplicateKeyException expected) {
            return 0;
        }
    }
}
