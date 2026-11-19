package lab.financialapi.authorization;

import java.util.UUID;
import java.util.function.Supplier;

import lab.financialapi.audit.GrantDeniedAuditService;
import lab.financialapi.common.web.ApiException;
import lab.financialapi.identity.AuthenticatedPrincipal;
import lab.financialapi.identity.PrincipalType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 성공 커밋과 롤백 후 거부 감사의 순서를 조정한다. 자체 업무 트랜잭션은 열지 않는다. */
@Service
public class AccessGrantService {
    private static final Logger log = LoggerFactory.getLogger(AccessGrantService.class);
    private final AccessGrantTransactionService changes;
    private final GrantDeniedAuditService deniedAudit;

    public AccessGrantService(AccessGrantTransactionService changes, GrantDeniedAuditService deniedAudit) {
        this.changes = changes;
        this.deniedAudit = deniedAudit;
    }

    public GrantResponse grant(
            AuthenticatedPrincipal principal,
            UUID accountId,
            GrantRequest request,
            String requestId) {
        requireCustomer(principal, accountId, "GRANT_CREATE", requestId);
        if (accountId == null || request == null || request.partnerId() == null || request.permission() == null) {
            throw ApiException.invalidRequest();
        }
        return execute(principal, accountId, "GRANT_CREATE", requestId,
                () -> changes.grant(principal, accountId, request, requestId));
    }

    public void revoke(
            AuthenticatedPrincipal principal,
            UUID accountId,
            UUID grantId,
            String requestId) {
        requireCustomer(principal, accountId, "GRANT_REVOKE", requestId);
        if (accountId == null || grantId == null) {
            throw ApiException.invalidRequest();
        }
        execute(principal, accountId, "GRANT_REVOKE", requestId, () -> {
            changes.revoke(principal, accountId, grantId, requestId);
            return null;
        });
    }

    private void requireCustomer(AuthenticatedPrincipal principal, UUID accountId, String action, String requestId) {
        if (principal == null || principal.id() == null || principal.type() == null) {
            throw ApiException.forbidden();
        }
        if (principal.type() != PrincipalType.CUSTOMER) {
            recordDenied(principal, accountId, action, "PRINCIPAL_TYPE_DENIED", requestId);
            throw ApiException.forbidden();
        }
    }

    private <T> T execute(
            AuthenticatedPrincipal principal,
            UUID accountId,
            String action,
            String requestId,
            Supplier<T> operation) {
        try {
            return operation.get();
        } catch (GrantRejectedException rejected) {
            // 호출은 트랜잭션 프록시 바깥이다. 업무 롤백을 마친 뒤 별도 감사만 커밋한다.
            recordDenied(principal, accountId, action, rejected.reason(), requestId);
            if ("ACTIVE_GRANT_CONFLICT".equals(rejected.reason())) {
                throw ApiException.conflict();
            }
            throw ApiException.notFound();
        } catch (RuntimeException error) {
            // SQL·예외 원문·토큰을 운영 오류에 넣지 않는다. 커밋 단계 실패도 성공으로 처리하지 않는다.
            log.error("Required grant change could not be committed");
            throw ApiException.unavailable();
        }
    }

    private void recordDenied(
            AuthenticatedPrincipal principal,
            UUID accountId,
            String action,
            String reason,
            String requestId) {
        try {
            deniedAudit.record(principal, accountId, action, false, reason, requestId);
        } catch (RuntimeException error) {
            log.error("Required grant denial audit could not be persisted");
            throw ApiException.unavailable();
        }
    }
}
