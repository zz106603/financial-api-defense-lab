package lab.financialapi.account;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * SQL에서 서비스로 전달하는 내부 상태다. 외부 응답으로 직렬화하지 않는다.
 * accountExists는 미존재/무권한의 감사 사유를 구분하고, accountId는 인가를 통과한 경우에만 채워진다.
 * 예: 타인 계좌면 accountExists=true, accountId와 금융 필드는 null이다.
 */
public record AccountReadRow(
        boolean accountExists,
        UUID accountId,
        String bankName,
        String maskedAccountNumber,
        BigDecimal balance) { }
