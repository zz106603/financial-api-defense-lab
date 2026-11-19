package lab.financialapi.authorization;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lab.financialapi.common.web.ApiException;
import lab.financialapi.common.web.RequestIds;
import lab.financialapi.identity.AuthenticatedPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 역할 검사와 거부 감사는 서비스에서 함께 수행해 선행 역할 차단으로 감사가 누락되지 않게 한다. */
@RestController
@RequestMapping("/api/v1/accounts/{accountId}/grants")
public class AccessGrantController {
    private final AccessGrantService grants;

    public AccessGrantController(AccessGrantService grants) {
        this.grants = grants;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public GrantResponse grant(
            @PathVariable String accountId,
            @RequestBody GrantRequest body,
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            HttpServletRequest request,
            HttpServletResponse response) {
        String requestId = RequestIds.getOrCreate(request, response);
        return grants.grant(principal, parseId(accountId), body, requestId);
    }

    @DeleteMapping("/{grantId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(
            @PathVariable String accountId,
            @PathVariable String grantId,
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            HttpServletRequest request,
            HttpServletResponse response) {
        String requestId = RequestIds.getOrCreate(request, response);
        grants.revoke(principal, parseId(accountId), parseId(grantId), requestId);
    }

    private UUID parseId(String value) {
        try {
            UUID id = UUID.fromString(value);
            if (!id.toString().equalsIgnoreCase(value)) {
                throw ApiException.invalidRequest();
            }
            return id;
        } catch (IllegalArgumentException error) {
            throw ApiException.invalidRequest();
        }
    }
}
