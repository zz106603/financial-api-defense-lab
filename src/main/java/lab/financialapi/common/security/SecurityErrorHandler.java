package lab.financialapi.common.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

/** 외부에는 고정된 메시지만 반환하고 예외나 JWT의 세부 정보를 노출하지 않는다. */
final class SecurityErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {
    /** 토큰이 없거나 인증에 실패했을 때 호출된다. 주체 DB 장애는 잘못된 토큰과 구분해 503을 반환한다. */
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException error)
            throws IOException {
        if (error instanceof OAuth2AuthenticationException oauth
                && "temporarily_unavailable".equals(oauth.getError().getErrorCode())) {
            write(response, 503, "SERVICE_UNAVAILABLE", "요청을 처리할 수 없습니다.");
        } else {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            write(response, 401, "UNAUTHENTICATED", "인증이 필요합니다.");
        }
    }

    /** 신원 확인을 마친 주체가 필요한 역할을 갖지 못했을 때 403을 반환한다. */
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException error)
            throws IOException {
        write(response, 403, "ACCESS_DENIED", "요청 권한이 없습니다.");
    }

    private void write(HttpServletResponse response, int status, String code, String message) throws IOException {
        // 이 보안 오류 응답용 ID를 서버에서 생성한다. 클라이언트가 보낸 추적 ID는 신뢰하지 않는다.
        String requestId = UUID.randomUUID().toString();
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("X-Request-Id", requestId);
        // code/message는 위의 고정값만 사용한다. 클라이언트 입력이나 예외 메시지를 연결하지 않는다.
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message
                + "\",\"requestId\":\"" + requestId + "\"}");
    }
}
