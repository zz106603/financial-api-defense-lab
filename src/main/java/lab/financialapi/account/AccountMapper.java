package lab.financialapi.account;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 주체 ID는 이후 인증 계층이 검증·매핑한 값만 전달한다. */
@Mapper
public interface AccountMapper {
    Optional<Account> findForCustomer(@Param("accountId") UUID accountId, @Param("customerId") UUID customerId);

    Optional<Account> findForPartner(@Param("accountId") UUID accountId, @Param("partnerId") UUID partnerId);

    // 빈 목록은 허용된 빈 계좌·페이지와 거부를 구분하지 않는다. API 성공 판정으로 사용하지 않는다.
    List<TransactionRecord> findTransactionsForCustomer(@Param("accountId") UUID accountId,
            @Param("customerId") UUID customerId, @Param("limit") int limit, @Param("offset") long offset);

    List<TransactionRecord> findTransactionsForPartner(@Param("accountId") UUID accountId,
            @Param("partnerId") UUID partnerId, @Param("limit") int limit, @Param("offset") long offset);
}
