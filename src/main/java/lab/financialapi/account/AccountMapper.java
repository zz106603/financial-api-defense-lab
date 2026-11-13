package lab.financialapi.account;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import lab.financialapi.identity.PrincipalType;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 주체 ID는 인증 계층이 검증·매핑한 값만 전달한다. 내부 투영을 API 응답으로 직접 노출하지 않는다. */
@Mapper
public interface AccountMapper {
    /**
     * API용 계좌 조회. XML의 readAccount가 같은 SQL 스냅샷에서 존재 여부와 인가를 평가한다.
     * 거부 시 상태 행만 반환하며 금융 필드는 null이다. 존재 여부 자체는 허용 조건이 아니다.
     */
    AccountReadRow readAccount(
            @Param("accountId") UUID accountId,
            @Param("principalId") UUID principalId,
            @Param("principalType") PrincipalType principalType);

    /**
     * API용 거래 조회. 최소 한 행의 상태를 반환하므로 허용된 빈 페이지와 권한 거부를 구분할 수 있다.
     * API 경로의 limit은 size+1이며 여분 한 행은 QueryService가 hasNext 계산에 사용한다.
     */
    List<TransactionPageRow> readTransactionPage(
            @Param("accountId") UUID accountId,
            @Param("principalId") UUID principalId,
            @Param("principalType") PrincipalType principalType,
            @Param("limit") int limit,
            @Param("offset") long offset);

    Optional<Account> findForCustomer(@Param("accountId") UUID accountId, @Param("customerId") UUID customerId);

    Optional<Account> findForPartner(@Param("accountId") UUID accountId, @Param("partnerId") UUID partnerId);

    // 빈 목록은 허용된 빈 계좌·페이지와 거부를 구분하지 않는다. API 성공 판정으로 사용하지 않는다.
    List<TransactionRecord> findTransactionsForCustomer(@Param("accountId") UUID accountId,
            @Param("customerId") UUID customerId, @Param("limit") int limit, @Param("offset") long offset);

    List<TransactionRecord> findTransactionsForPartner(@Param("accountId") UUID accountId,
            @Param("partnerId") UUID partnerId, @Param("limit") int limit, @Param("offset") long offset);
}
