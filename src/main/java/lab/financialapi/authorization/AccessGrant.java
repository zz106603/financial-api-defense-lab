package lab.financialapi.authorization;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AccessGrant(UUID id, UUID accountId, UUID partnerId, Permission permission,
                          UUID grantedBy, OffsetDateTime grantedAt, OffsetDateTime revokedAt) { }
