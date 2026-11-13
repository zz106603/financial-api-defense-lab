package lab.financialapi.authorization;

import java.util.List;
import java.util.UUID;

import lab.financialapi.account.AccountMapper;
import lab.financialapi.account.AccountReadRow;
import lab.financialapi.account.TransactionPageRow;
import lab.financialapi.common.web.ApiException;
import lab.financialapi.identity.AuthenticatedPrincipal;
import lab.financialapi.identity.PrincipalType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 검증된 인증 주체만 SQL에 전달하고 데이터와 권한을 동시에 평가하는 조회 경계다.
 * 권한을 먼저 조회한 뒤 무조건 데이터를 읽는 방식 대신, Mapper의 실제 조회 SQL에 인가 조건을 적용한다.
 * REQUIRES_NEW로 호출 측 트랜잭션의 오래된 스냅샷을 물려받지 않고 READ_COMMITTED로 새 SQL을 실행한다.
 * 메서드가 프록시를 통해 반환되면 읽기 트랜잭션이 끝나고, 이후 QueryService가 감사 트랜잭션을 시작한다.
 */
@Service
@Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW)
public class FinancialReadAuthorizationService {
    private final AccountMapper accounts;

    public FinancialReadAuthorizationService(AccountMapper accounts) {
        this.accounts = accounts;
    }

    /** 고객이면 소유권, 제휴사면 활성 ACCOUNT_READ를 SQL에서 검사한 내부 결과를 반환한다. */
    public AccountReadRow account(AuthenticatedPrincipal principal, UUID accountId) {
        requirePrincipal(principal);
        return accounts.readAccount(accountId, principal.id(), principal.type());
    }

    /** 거래 조회는 별도의 TRANSACTION_READ가 필요하다. ACCOUNT_READ만으로 허용하지 않는다. */
    public List<TransactionPageRow> transactions(
            AuthenticatedPrincipal principal,
            UUID accountId,
            int limit,
            long offset) {
        requirePrincipal(principal);
        return accounts.readTransactionPage(accountId, principal.id(), principal.type(), limit, offset);
    }

    /** JWT 인증 결과를 전제로 하되, 내부 호출에서도 누락/지원하지 않는 주체를 SQL로 보내지 않는다. */
    private void requirePrincipal(AuthenticatedPrincipal principal) {
        if (principal == null || principal.id() == null
                || (principal.type() != PrincipalType.CUSTOMER && principal.type() != PrincipalType.PARTNER)) {
            throw ApiException.forbidden();
        }
    }
}
