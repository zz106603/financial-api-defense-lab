package lab.financialapi.audit;

import java.util.UUID;

import lab.financialapi.identity.AuthenticatedPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 변경 트랜잭션의 실패·호출 측 롤백과 독립적으로 거부 이벤트를 커밋한다. */
@Service
public class GrantDeniedAuditService {
    private final GrantAuditService audit;

    public GrantDeniedAuditService(GrantAuditService audit) {
        this.audit = audit;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(
            AuthenticatedPrincipal principal,
            UUID accountId,
            String action,
            boolean allowed,
            String reasonCode,
            String requestId) {
        audit.record(principal, accountId, action, allowed, reasonCode, requestId);
    }
}
