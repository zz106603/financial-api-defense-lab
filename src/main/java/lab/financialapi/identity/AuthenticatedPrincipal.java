package lab.financialapi.identity;

import java.security.Principal;
import java.util.UUID;

/**
 * 검증된 JWT의 sub와 유형으로 DB에서 식별한 주체. 토큰이나 계좌 권한은 보유하지 않는다.
 * 이후 Controller에서 {@code @AuthenticationPrincipal AuthenticatedPrincipal principal}로 받을 수 있다.
 * id는 고객 또는 제휴사 테이블의 UUID이고, 어느 테이블의 ID인지는 type으로 구분한다.
 */
public record AuthenticatedPrincipal(UUID id, PrincipalType type) implements Principal {
    /** Spring Security에서 사용하는 주체 이름도 외부 sub 대신 내부 DB UUID로 통일한다. */
    @Override
    public String getName() {
        return id.toString();
    }
}
