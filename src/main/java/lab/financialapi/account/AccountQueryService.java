package lab.financialapi.account;

import java.util.UUID;

import lab.financialapi.audit.ReadAuditService;
import lab.financialapi.authorization.FinancialReadAuthorizationService;
import lab.financialapi.authorization.Permission;
import lab.financialapi.common.web.ApiException;
import lab.financialapi.identity.AuthenticatedPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Controller → 인가를 포함한 조회 → 필수 감사 커밋 → 응답의 순서를 조정한다.
 * 이 클래스 전체를 하나의 트랜잭션으로 묶지 않고, 조회와 감사를 각각 별도 Bean의 경계로 실행한다.
 * DB에서 데이터를 읽었어도 감사가 커밋되기 전에는 Controller에 금융정보를 반환하지 않는다.
 */
@Service
public class AccountQueryService {
    public static final int MAX_PAGE_SIZE = 100;
    private static final Logger log = LoggerFactory.getLogger(AccountQueryService.class);

    private final FinancialReadAuthorizationService authorization;
    private final ReadAuditService audit;

    public AccountQueryService(FinancialReadAuthorizationService authorization, ReadAuditService audit) {
        this.authorization = authorization;
        this.audit = audit;
    }

    /** 계좌 조회의 허용/거부를 먼저 감사한 뒤 공개 DTO 또는 동일한 외부 404로 변환한다. */
    public AccountResponse account(
            AuthenticatedPrincipal principal,
            UUID accountId,
            String requestId) {
        var row = authorization.account(principal, accountId);
        // SQL은 미존재/무권한에도 상태 행을 반환한다. 행 자체가 없으면 정상적인 거부로 추측하지 않는다.
        if (row == null) {
            throw ApiException.unavailable();
        }

        // 인가된 계좌 ID가 있어야 허용이다. accountExists만으로는 조회를 허용하지 않는다.
        boolean allowed = row.accountId() != null;
        record(principal, accountId, Permission.ACCOUNT_READ, allowed, row.accountExists(), requestId);
        // 감사 이후 예외를 던져도 독립 커밋한 거부 기록은 남는다. 미존재와 무권한의 외부 응답은 같다.
        if (!allowed) {
            throw ApiException.notFound();
        }

        // 내부 상태 행을 그대로 반환하지 않고 허용된 네 필드만 응답한다.
        return new AccountResponse(
                row.accountId(),
                row.bankName(),
                row.maskedAccountNumber(),
                row.balance());
    }

    /** 거래 권한과 페이지를 함께 조회한다. 빈 목록 여부가 아니라 별도의 인가 상태로 허용을 판단한다. */
    public TransactionsResponse transactions(
            AuthenticatedPrincipal principal,
            UUID accountId,
            int page,
            int size,
            String requestId) {
        // SQL에 전달하기 전에 범위를 제한한다. 입력 오류를 객체별 권한 위반으로 분류하지 않는다.
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw ApiException.invalidRequest();
        }

        // int 곱셈을 피하고 큰 page도 다른 페이지로 되돌아가지 않도록 long으로 계산한다.
        long offset = (long) page * size;

        // 한 행 더 읽어 다음 페이지 존재 여부를 구한다. 전체 거래 건수 조회는 필요하지 않다.
        var rows = authorization.transactions(principal, accountId, size + 1, offset);
        if (rows == null || rows.isEmpty()) {
            throw ApiException.unavailable();
        }

        // 모든 행의 상태는 같은 SQL에서 계산된다. 첫 행은 거래가 없는 페이지에도 유지된다.
        var state = rows.getFirst();
        boolean allowed = state.authorizedAccountId() != null;
        record(principal, accountId, Permission.TRANSACTION_READ, allowed, state.accountExists(), requestId);
        if (!allowed) {
            throw ApiException.notFound();
        }

        // 거래 ID가 null인 행은 빈 페이지의 상태 행이므로 실제 응답 items에서는 제외한다.
        var transactions = rows.stream()
                .filter(row -> row.transactionId() != null)
                .toList();

        // size+1로 가져온 여분은 hasNext 판단에만 쓰고, 응답에는 size개까지만 담는다.
        // Instant로 바꿔 같은 거래 시각을 UTC로 표현하고 금액은 BigDecimal 그대로 전달한다.
        var items = transactions.stream()
                .limit(size)
                .map(row -> new TransactionsResponse.Item(
                        row.transactionId(),
                        row.occurredAt().toInstant(),
                        row.amount(),
                        row.description()))
                .toList();

        return new TransactionsResponse(accountId, items, page, size, transactions.size() > size);
    }

    /** 내부 감사는 사유를 구분하되 외부 오류에는 계좌의 존재 여부나 세부 권한을 노출하지 않는다. */
    private void record(
            AuthenticatedPrincipal principal,
            UUID accountId,
            Permission action,
            boolean allowed,
            boolean exists,
            String requestId) {
        String reason = allowed ? "ACCESS_ALLOWED"
                : exists ? "OBJECT_ACCESS_DENIED"
                : "RESOURCE_NOT_FOUND";

        try {
            // 별도 Bean 프록시로 호출해 REQUIRES_NEW의 커밋이 끝난 뒤 처리를 계속한다.
            audit.record(principal, accountId, action, allowed, reason, requestId);
        } catch (RuntimeException error) {
            // 감사 삽입뿐 아니라 프록시의 커밋 단계 실패도 여기서 차단한다. 원래 금융 응답은 반환하지 않는다.
            log.error("Required financial read audit could not be persisted");
            throw ApiException.unavailable();
        }
    }
}
