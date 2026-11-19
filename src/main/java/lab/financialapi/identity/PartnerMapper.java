package lab.financialapi.identity;

import java.util.Optional;
import java.util.UUID;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PartnerMapper {
    boolean existsById(@Param("id") UUID id);

    Optional<Partner> findByAuthSubject(@Param("authSubject") String authSubject);
}
