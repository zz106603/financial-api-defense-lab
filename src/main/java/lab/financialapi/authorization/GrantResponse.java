package lab.financialapi.authorization;

import java.time.Instant;
import java.util.UUID;

public record GrantResponse(
        UUID grantId,
        UUID accountId,
        UUID partnerId,
        Permission permission,
        Instant grantedAt) { }
