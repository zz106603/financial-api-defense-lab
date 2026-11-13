package lab.financialapi.audit;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import lab.financialapi.authorization.Permission;
import lab.financialapi.identity.AuthenticatedPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 조회 허용/거부를 기록하는 최소 감사 서비스다. 이상 접근을 집계하거나 알림을 만드는 탐지 서비스는 아니다.
 * QueryService가 이 Bean의 프록시를 호출하므로 REQUIRES_NEW가 적용되어 업무 롤백과 독립적으로 커밋한다.
 */
@Service
public class ReadAuditService {
    private final AuditEventMapper events;
    private final ResourceReferenceGenerator references;

    public ReadAuditService(AuditEventMapper events, ResourceReferenceGenerator references) {
        this.events = events;
        this.references = references;
    }

    /**
     * 외부 업무 트랜잭션이 있으면 잠시 중단하고 별도 트랜잭션에 감사 한 건을 저장한다.
     * 메서드 본문이 끝난 뒤 프록시가 커밋까지 마쳐야 호출자에게 정상 반환된다.
     * 저장/커밋 실패는 호출자에게 전달되어 금융정보 응답을 503으로 중단시킨다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(
            AuthenticatedPrincipal principal,
            UUID accountId,
            Permission action,
            boolean allowed,
            String reasonCode,
            String requestId) {
        // 금융 데이터나 JWT를 인자로 받지 않고 검증된 주체·행위·가명 대상만 저장한다.
        // DB 주체 UUID는 요청자 식별에 사용하고 대상 계좌 UUID는 HMAC 가명으로 바꿔 저장한다.
        var event = new AuditEvent(
                UUID.randomUUID(),
                OffsetDateTime.now(ZoneOffset.UTC),
                principal.type(),
                principal.id(),
                action.name(),
                references.accountReference(accountId),
                allowed ? "ALLOWED" : "DENIED",
                reasonCode,
                requestId);

        // 예외가 없더라도 실제로 한 건이 저장되지 않았다면 감사 성공으로 취급하지 않는다.
        if (events.insert(event) != 1) {
            throw new IllegalStateException("Required read audit was not persisted");
        }
    }
}
