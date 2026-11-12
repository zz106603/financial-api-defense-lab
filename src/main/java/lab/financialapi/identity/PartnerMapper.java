package lab.financialapi.identity;

import java.util.Optional;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PartnerMapper {
    Optional<Partner> findByAuthSubject(@Param("authSubject") String authSubject);
}
