package lab.financialapi.audit;

import java.time.OffsetDateTime;
import java.util.UUID;

import lab.financialapi.identity.PrincipalType;

/** resourceRef에는 후속 감사 서비스가 생성한 안정적인 가명 참조만 전달한다. */
public record AuditEvent(UUID id, OffsetDateTime occurredAt, PrincipalType principalType, UUID principalId,
                         String action, String resourceRef, String result, String reasonCode, String requestId) { }
