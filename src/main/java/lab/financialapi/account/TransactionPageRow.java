package lab.financialapi.account;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 거래 데이터와 인가 상태를 함께 전달하는 내부 행이다. API 응답 DTO와 구분한다.
 * 허용된 빈 페이지는 authorizedAccountId가 있고 transactionId는 null인 상태 행 한 개가 남는다.
 * 무권한/미존재는 authorizedAccountId도 null이다. accountExists로 두 경우의 감사 사유만 구분한다.
 * 실제 거래가 있으면 각 거래 행에 같은 계좌 상태가 함께 담긴다.
 */
public record TransactionPageRow(
        boolean accountExists,
        UUID authorizedAccountId,
        UUID transactionId,
        OffsetDateTime occurredAt,
        BigDecimal amount,
        String description) { }
