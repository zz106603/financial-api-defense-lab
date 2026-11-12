package lab.financialapi.authorization;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 이후 서비스가 소유권 검증·감사 저장과 동일 트랜잭션으로 호출해야 한다. */
@Mapper
public interface AccessGrantMapper {
    int insert(AccessGrant grant);

    Optional<AccessGrant> findForOwner(@Param("grantId") UUID grantId, @Param("accountId") UUID accountId,
            @Param("customerId") UUID customerId);

    int revoke(@Param("grantId") UUID grantId, @Param("accountId") UUID accountId,
            @Param("customerId") UUID customerId, @Param("revokedAt") OffsetDateTime revokedAt);
}
