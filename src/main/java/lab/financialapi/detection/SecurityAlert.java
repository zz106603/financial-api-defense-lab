package lab.financialapi.detection;

import java.time.OffsetDateTime;
import java.util.UUID;

import lab.financialapi.identity.PrincipalType;

public record SecurityAlert(UUID id, String ruleId, PrincipalType principalType, UUID principalId,
                            OffsetDateTime windowStart, OffsetDateTime windowEnd, int violationCount,
                            int distinctResourceCount, OffsetDateTime detectedAt) { }
