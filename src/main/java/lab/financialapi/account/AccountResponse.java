package lab.financialapi.account;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 감사가 성공한 계좌 조회의 외부 응답 DTO다. Controller 반환 후 Spring이 JSON으로 직렬화한다.
 * 내부 소유자·생성 시각·인가 상태를 제외하고, 금액은 BigDecimal로 유지해 double 변환을 피한다.
 */
public record AccountResponse(
        UUID accountId,
        String bankName,
        String maskedAccountNumber,
        BigDecimal balance) { }
