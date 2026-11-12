package lab.financialapi.account;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** 영속성 모델. API 응답에서는 내부 소유자·생성 시각을 별도 DTO로 제외해야 한다. */
public record Account(UUID id, UUID customerId, String bankName, String maskedAccountNumber,
                      BigDecimal balance, OffsetDateTime createdAt) { }
