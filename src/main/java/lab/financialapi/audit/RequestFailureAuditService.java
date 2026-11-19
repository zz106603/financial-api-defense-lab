package lab.financialapi.audit;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import lab.financialapi.identity.AuthenticatedPrincipal;
import lab.financialapi.identity.PrincipalType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 새 인증·입력 오류 감사만 독립 저장한다. 실패 응답 유지 여부는 호출 경계가 결정한다. */
@Service
public class RequestFailureAuditService {
    private final AuditEventMapper events;
    private final ResourceReferenceGenerator references;

    public RequestFailureAuditService(AuditEventMapper events, ResourceReferenceGenerator references) {
        this.events = events;
        this.references = references;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(
            AuthenticatedPrincipal principal,
            UUID accountId,
            String action,
            String reason,
            String requestId) {
        boolean identified = principal != null && principal.id() != null
                && (principal.type() == PrincipalType.CUSTOMER || principal.type() == PrincipalType.PARTNER);
        var event = new AuditEvent(
                UUID.randomUUID(),
                OffsetDateTime.now(ZoneOffset.UTC),
                identified ? principal.type() : PrincipalType.ANONYMOUS,
                identified ? principal.id() : null,
                action,
                accountId == null ? null : references.accountReference(accountId),
                "DENIED",
                reason,
                requestId);
        if (events.insert(event) != 1) {
            throw new IllegalStateException("Request failure audit was not persisted");
        }
    }
}
