package lab.financialapi.account;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lab.financialapi.common.web.ApiException;
import lab.financialapi.common.web.RequestIds;
import lab.financialapi.identity.AuthenticatedPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * JWT 인증을 통과한 금융 조회 요청의 진입점이다.
 * URL/페이지 입력을 정리한 뒤 AccountQueryService에 전달하며 직접 권한 조회나 감사 저장을 하지 않는다.
 */
@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {
    private final AccountQueryService queries;

    public AccountController(AccountQueryService queries) {
        this.queries = queries;
    }

    /** 계좌 기본정보 조회. principal은 요청 파라미터가 아니라 Spring Security가 보관한 DB 주체다. */
    @GetMapping("/{accountId}")
    public AccountResponse account(
            @PathVariable String accountId,
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            HttpServletRequest request,
            HttpServletResponse response) {
        // 서비스가 남길 감사와 성공/오류 응답을 같은 서버 생성 ID로 연결한다.
        String requestId = RequestIds.getOrCreate(request, response);
        return queries.account(principal, parseAccountId(accountId), requestId);
    }

    /** 거래 조회의 생략된 페이지 값만 채운다. 음수·상한 검증과 offset 계산은 서비스가 담당한다. */
    @GetMapping("/{accountId}/transactions")
    public TransactionsResponse transactions(
            @PathVariable String accountId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            HttpServletRequest request,
            HttpServletResponse response) {
        String requestId = RequestIds.getOrCreate(request, response);
        return queries.transactions(principal, parseAccountId(accountId), page, size, requestId);
    }

    /** 잘못된 계좌 ID는 DB 조회 전에 400으로 처리하며 입력 원문을 오류 응답에 넣지 않는다. */
    private UUID parseAccountId(String value) {
        try {
            UUID id = UUID.fromString(value);
            // UUID.fromString이 허용하는 축약형은 API의 UUID 표현으로 받지 않는다.
            if (!id.toString().equalsIgnoreCase(value)) {
                throw ApiException.invalidRequest();
            }

            return id;
        } catch (IllegalArgumentException error) {
            throw ApiException.invalidRequest();
        }
    }
}
