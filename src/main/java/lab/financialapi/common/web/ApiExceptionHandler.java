package lab.financialapi.common.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 금융 조회 Controller 실행 중 발생한 오류를 안전한 JSON 응답으로 바꾼다.
 * Controller 진입 전 JWT 인증 오류는 기존 SecurityErrorHandler가 처리한다.
 * 감사 저장은 이 클래스에서 하지 않고, QueryService가 감사 완료 후 던진 오류를 응답으로 변환한다.
 */
@RestControllerAdvice(basePackages = "lab.financialapi.account")
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** 서비스가 정한 고정 업무 오류의 상태·코드를 유지한다. */
    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorBody> businessError(
            ApiException error,
            HttpServletRequest request,
            HttpServletResponse response) {
        return error(error, request, response);
    }

    /** page=abc처럼 Controller 인자 변환 단계에서 실패해도 원문 대신 동일한 400을 반환한다. */
    @ExceptionHandler({
            MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class
    })
    ResponseEntity<ErrorBody> invalidInput(
            Exception error,
            HttpServletRequest request,
            HttpServletResponse response) {
        return error(ApiException.invalidRequest(), request, response);
    }

    /** 메서드 보안 등에서 인증된 주체의 접근을 거부한 경우를 403으로 변환한다. */
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ErrorBody> forbidden(
            AccessDeniedException error,
            HttpServletRequest request,
            HttpServletResponse response) {
        return error(ApiException.forbidden(), request, response);
    }

    /** 조회/트랜잭션 장애의 SQL·예외 상세를 노출하지 않고 금융 응답을 503으로 중단한다. */
    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ResponseEntity<ErrorBody> storageFailure(
            Exception error,
            HttpServletRequest request,
            HttpServletResponse response) {
        log.error("Financial read storage operation failed");
        return error(ApiException.unavailable(), request, response);
    }

    private ResponseEntity<ErrorBody> error(
            ApiException error,
            HttpServletRequest request,
            HttpServletResponse response) {
        // 조회가 이미 감사한 요청이면 그때의 ID를 재사용해 오류 본문·헤더·감사를 연결한다.
        return ResponseEntity.status(error.status())
                .body(new ErrorBody(
                        error.code(),
                        error.getMessage(),
                        RequestIds.getOrCreate(request, response)));
    }

    /** 외부 오류 필드는 세 개로 제한한다. exception/cause 같은 내부 객체를 직렬화하지 않는다. */
    public record ErrorBody(String code, String message, String requestId) { }
}
