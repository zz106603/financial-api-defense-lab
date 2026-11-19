package lab.financialapi.authorization;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import lab.financialapi.audit.GrantAuditService;
import lab.financialapi.identity.AuthenticatedPrincipal;
import lab.financialapi.identity.PartnerMapper;
import lab.financialapi.identity.PrincipalType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 메서드 반환 후 프록시 커밋이 끝나야 조정 서비스가 성공 응답을 반환할 수 있다. */
@Service
public class AccessGrantTransactionService {
    private final AccessGrantMapper grants;
    private final PartnerMapper partners;
    private final GrantAuditService audit;

    public AccessGrantTransactionService(
            AccessGrantMapper grants,
            PartnerMapper partners,
            GrantAuditService audit) {
        this.grants = grants;
        this.partners = partners;
        this.audit = audit;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public GrantResponse grant(
            AuthenticatedPrincipal principal,
            UUID accountId,
            GrantRequest request,
            String requestId) {
        requireOwner(principal, accountId);
        if (!partners.existsById(request.partnerId())) {
            throw new GrantRejectedException("RESOURCE_NOT_FOUND");
        }

        var grant = new AccessGrant(
                UUID.randomUUID(),
                accountId,
                request.partnerId(),
                request.permission(),
                principal.id(),
                OffsetDateTime.now(ZoneOffset.UTC),
                null);
        // 중복 사전 조회 없이 부분 UNIQUE에 대응하는 충돌 대상만 무시한다. 다른 제약 오류는 전파한다.
        if (grants.insertActive(grant) != 1) {
            throw new GrantRejectedException("ACTIVE_GRANT_CONFLICT");
        }

        audit.record(principal, accountId, "GRANT_CREATE", true, "GRANT_CREATED", requestId);
        return new GrantResponse(
                grant.id(), accountId, grant.partnerId(), grant.permission(), grant.grantedAt().toInstant());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public void revoke(
            AuthenticatedPrincipal principal,
            UUID accountId,
            UUID grantId,
            String requestId) {
        requireOwner(principal, accountId);
        int changed = grants.revoke(grantId, accountId, principal.id(), OffsetDateTime.now(ZoneOffset.UTC));
        String reason;
        if (changed == 1) {
            reason = "GRANT_REVOKED";
        } else if (changed == 0) {
            // 경합한 UPDATE가 대기 후 0행이면 새 READ COMMITTED 스냅샷에서 실제 회수 상태를 확인한다.
            var previous = grants.findForOwner(grantId, accountId, principal.id())
                    .orElseThrow(() -> new GrantRejectedException("RESOURCE_NOT_FOUND"));
            if (previous.revokedAt() == null) {
                throw new IllegalStateException("Grant revocation did not change an active grant");
            }
            reason = "GRANT_ALREADY_REVOKED";
        } else {
            throw new IllegalStateException("Grant revocation changed an unexpected number of rows");
        }

        audit.record(principal, accountId, "GRANT_REVOKE", true, reason, requestId);
    }

    private void requireOwner(AuthenticatedPrincipal principal, UUID accountId) {
        // 트랜잭션 Bean을 직접 호출해도 제휴사가 소유 고객 ID로 변경할 수 없게 기본 거부한다.
        if (principal == null || principal.type() != PrincipalType.CUSTOMER || principal.id() == null) {
            throw new GrantRejectedException("PRINCIPAL_TYPE_DENIED");
        }
        UUID owner = grants.findAccountOwner(accountId)
                .orElseThrow(() -> new GrantRejectedException("RESOURCE_NOT_FOUND"));
        if (!owner.equals(principal.id())) {
            throw new GrantRejectedException("OBJECT_ACCESS_DENIED");
        }
    }
}
