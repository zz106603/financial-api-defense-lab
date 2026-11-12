package lab.financialapi.identity;

import java.util.Optional;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CustomerMapper {
    Optional<Customer> findByAuthSubject(@Param("authSubject") String authSubject);
}
