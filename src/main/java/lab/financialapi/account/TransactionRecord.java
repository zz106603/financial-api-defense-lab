package lab.financialapi.account;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record TransactionRecord(UUID id, UUID accountId, OffsetDateTime occurredAt,
                                BigDecimal amount, String description) { }
