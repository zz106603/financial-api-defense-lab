package lab.financialapi.audit;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import lab.financialapi.identity.AuthenticatedPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 성공 감사는 권한 변경과 같은 트랜잭션에 참여하며 단독 자동 커밋을 허용하지 않는다. */
@Service
public class GrantAuditService {
    private final AuditEventMapper events;
    private final ResourceReferenceGenerator references;

    public GrantAuditService(AuditEventMapper events, ResourceReferenceGenerator references) {
        this.events = events;
        this.references = references;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(
            AuthenticatedPrincipal principal,
            UUID accountId,
            String action,
            boolean allowed,
            String reasonCode,
            String requestId) {
        var event = new AuditEvent(
                UUID.randomUUID(),
                OffsetDateTime.now(ZoneOffset.UTC),
                principal.type(),
                principal.id(),
                action,
                references.accountReference(accountId),
                allowed ? "ALLOWED" : "DENIED",
                reasonCode,
                requestId);
        if (events.insert(event) != 1) {
            throw new IllegalStateException("Required grant audit was not persisted");
        }
    }
}
