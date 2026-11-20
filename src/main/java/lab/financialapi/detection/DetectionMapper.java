package lab.financialapi.detection;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import lab.financialapi.identity.PrincipalType;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface DetectionMapper {
    OffsetDateTime currentTime();

    List<DetectionCandidate> candidatesInWindow(
            @Param("windowEnd") OffsetDateTime windowEnd,
            @Param("windowSeconds") int windowSeconds);

    Optional<OffsetDateTime> claimAfterCooldown(
            @Param("ruleId") String ruleId,
            @Param("principalType") PrincipalType principalType,
            @Param("principalId") UUID principalId,
            @Param("windowEnd") OffsetDateTime windowEnd,
            @Param("cooldownSeconds") int cooldownSeconds);
}
