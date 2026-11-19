package lab.financialapi.authorization;

/** 트랜잭션 롤백 이후 독립 감사에 전달할 내부 판단 사유. 외부 응답에 직접 직렬화하지 않는다. */
final class GrantRejectedException extends RuntimeException {
    private final String reason;

    GrantRejectedException(String reason) {
        this.reason = reason;
    }

    String reason() {
        return reason;
    }
}
