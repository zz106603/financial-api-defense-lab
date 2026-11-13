package lab.financialapi.common.web;

import org.springframework.http.HttpStatus;

/**
 * 서비스/Controller가 던지는, 고정 상태·코드·메시지로 제한한 업무 오류다.
 * 실제 JSON 응답 생성은 ApiExceptionHandler가 담당하며 원문 입력·SQL·권한 관계를 담지 않는다.
 */
public final class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    private ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    /** 계좌가 없거나 권한이 없어도 외부에는 같은 오류를 보낸다. 내부 감사만 사유를 구분한다. */
    public static ApiException notFound() {
        return new ApiException(
                HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND",
                "요청한 리소스를 찾을 수 없습니다.");
    }

    /** UUID/페이지 입력 오류이며 객체별 권한 위반과 구분한다. */
    public static ApiException invalidRequest() {
        return new ApiException(
                HttpStatus.BAD_REQUEST,
                "INVALID_REQUEST",
                "요청 형식이나 값이 올바르지 않습니다.");
    }

    /** 인증 이후에도 지원하지 않는 주체 등은 기본 거부한다. 토큰 인증 실패 401과는 다르다. */
    public static ApiException forbidden() {
        return new ApiException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "요청 권한이 없습니다.");
    }

    /** DB 조회나 필수 감사가 실패하면 금융정보 대신 일반적인 503으로 응답한다. */
    public static ApiException unavailable() {
        return new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "SERVICE_UNAVAILABLE",
                "요청을 처리할 수 없습니다.");
    }
}
