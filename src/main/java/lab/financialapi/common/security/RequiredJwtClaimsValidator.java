package lab.financialapi.common.security;

import java.time.Instant;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * 서명이 맞는 JWT라도 우리 API의 필수 클레임 조건에 맞지 않으면 거부한다.
 * DB 사용자 조회는 하지 않으며, 서명·issuer·시각의 유효성 검사는 Decoder에 연결한 다른 검증기가 맡는다.
 */
final class RequiredJwtClaimsValidator implements OAuth2TokenValidator<Jwt> {
    private final String audience;

    RequiredJwtClaimsValidator(String audience) {
        this.audience = audience;
    }

    /** 통과 결과만 반환한다. 실패하면 Resource Server가 Converter 호출 전에 인증을 중단한다. */
    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        // sub는 DB의 auth_subject와 연결할 외부 식별자이며, 우리 DB의 고객/제휴사 UUID 자체가 아니다.
        Object sub = jwt.getClaims().get("sub");
        Object type = jwt.getClaims().get("principal_type");
        // exp의 존재도 강제한다. nbf는 선택 항목이며 있으면 별도의 시각 검증기가 검사한다.
        // aud는 여러 대상일 수 있으므로, 설정한 API 대상이 포함되어 있는지 확인한다.
        if (!(sub instanceof String subject) || subject.isBlank()
                || !("CUSTOMER".equals(type) || "PARTNER".equals(type))
                || !(jwt.getClaims().get("exp") instanceof Instant)
                || jwt.getAudience() == null || !jwt.getAudience().contains(audience)) {
            // 외부 응답에 어떤 클레임이 틀렸는지 상세 정보를 넣지 않는다.
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        }
        return OAuth2TokenValidatorResult.success();
    }
}
