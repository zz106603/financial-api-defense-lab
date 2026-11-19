package lab.financialapi.common.web;

import java.util.UUID;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lab.financialapi.audit.RequestFailureAuditService;
import lab.financialapi.identity.AuthenticatedPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.util.ServletRequestPathUtils;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/** 정해진 금융 API의 오류만 분류하며 입력 원문·예외·JWT를 감사 서비스로 넘기지 않는다. */
@Component
public class RequestFailureAuditor {
    private static final Logger log = LoggerFactory.getLogger(RequestFailureAuditor.class);
    private static final String RECORDED = RequestFailureAuditor.class.getName() + ".recorded";
    private static final List<Route> ROUTES = List.of(
            route("GET", "/api/v1/accounts/{accountId}", "ACCOUNT_READ"),
            route("GET", "/api/v1/accounts/{accountId}/transactions", "TRANSACTION_READ"),
            route("POST", "/api/v1/accounts/{accountId}/grants", "GRANT_CREATE"),
            route("DELETE", "/api/v1/accounts/{accountId}/grants/{grantId}", "GRANT_REVOKE"));
    private final RequestFailureAuditService audit;

    public RequestFailureAuditor(RequestFailureAuditService audit) {
        this.audit = audit;
    }

    public void authenticationFailure(HttpServletRequest request, HttpServletResponse response) {
        // 인증에 실패한 JWT의 sub·유형이나 남아 있는 SecurityContext를 주체로 사용하지 않는다.
        record(request, response, null, "AUTHENTICATION_FAILED");
    }

    public void invalidRequest(HttpServletRequest request, HttpServletResponse response) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        AuthenticatedPrincipal principal = authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof AuthenticatedPrincipal verified ? verified : null;
        record(request, response, principal, "INVALID_REQUEST");
    }

    private void record(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticatedPrincipal principal,
            String reason) {
        if (request.getAttribute(RECORDED) != null) {
            return;
        }
        try {
            Target target = target(request);
            if (target == null) {
                return;
            }
            // 저장 실패도 같은 요청에서 재귀적으로 다시 감사하지 않는다.
            request.setAttribute(RECORDED, Boolean.TRUE);
            String requestId = RequestIds.getOrCreate(request, response);
            audit.record(principal, target.accountId(), target.action(), reason, requestId);
        } catch (RuntimeException error) {
            // 프록시 커밋 실패도 포함한다. 기존 필수 감사와 달리 원래 401/400을 유지한다.
            log.error("Supplementary request failure audit could not be persisted");
        }
    }

    private Target target(HttpServletRequest request) {
        // MVC와 같은 세그먼트별 디코딩을 사용한다. URI 전체를 임의로 디코딩해 경계를 바꾸지 않는다.
        var path = ServletRequestPathUtils.hasParsedRequestPath(request)
                ? ServletRequestPathUtils.getParsedRequestPath(request)
                : ServletRequestPathUtils.parseAndCache(request);
        for (Route route : ROUTES) {
            if (!route.method().equals(request.getMethod())) {
                continue;
            }
            var match = route.pattern().matchAndExtract(path.pathWithinApplication());
            if (match != null) {
                return new Target(route.action(), parseAccountId(match.getUriVariables().get("accountId")));
            }
        }
        return null;
    }

    private UUID parseAccountId(String value) {
        try {
            UUID parsed = UUID.fromString(value);
            return parsed.toString().equalsIgnoreCase(value) ? parsed : null;
        } catch (IllegalArgumentException ignored) {
            // 형식이 잘못된 식별자는 저장하지 않고 대상이 없는 감사로 기록한다.
            return null;
        }
    }

    private static Route route(String method, String path, String action) {
        return new Route(method, PathPatternParser.defaultInstance.parse(path), action);
    }

    private record Target(String action, UUID accountId) { }

    private record Route(String method, PathPattern pattern, String action) { }
}
