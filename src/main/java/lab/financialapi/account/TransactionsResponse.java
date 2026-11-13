package lab.financialapi.account;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 허용된 거래 페이지만 응답한다. hasNext는 추가로 읽은 한 행의 존재 여부이며 전체 건수는 반환하지 않는다. */
public record TransactionsResponse(
        UUID accountId,
        List<Item> items,
        int page,
        int size,
        boolean hasNext) {
    /** 외부 거래 필드만 제공한다. Instant는 같은 시각을 UTC로 표현하며 금액은 정확한 소수로 유지한다. */
    public record Item(
            UUID transactionId,
            Instant occurredAt,
            BigDecimal amount,
            String description) { }
}
