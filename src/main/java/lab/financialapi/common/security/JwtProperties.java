package lab.financialapi.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;
import org.springframework.util.Assert;

/**
 * application.yaml과 외부 환경변수에서 받은 JWT 검증 기준이다.
 * issuer는 신뢰하는 발급자(iss), audience는 이 API의 대상 식별값(aud), publicKeyLocation은 공개키 위치다.
 * issuer 주소로 인증 서버에 접속하지 않으며, 개인키나 토큰을 이 설정에 보관하지 않는다.
 */
@ConfigurationProperties("security.jwt")
public record JwtProperties(String issuer, String audience, Resource publicKeyLocation) {
    public JwtProperties {
        // 신뢰 기준이 없는 상태로 요청을 받지 않도록 설정 바인딩 단계에서 기동을 실패시킨다.
        Assert.hasText(issuer, "security.jwt.issuer is required");
        Assert.hasText(audience, "security.jwt.audience is required");
        Assert.notNull(publicKeyLocation, "security.jwt.public-key-location is required");
    }
}
