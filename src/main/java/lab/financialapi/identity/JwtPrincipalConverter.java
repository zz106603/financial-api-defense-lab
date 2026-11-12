package lab.financialapi.identity;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.convert.converter.Converter;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Decoder의 서명·클레임 검증을 통과한 JWT를 우리 DB의 요청 주체로 변환한다.
 * 인증 성공은 계좌 접근 허용을 뜻하지 않는다. 계좌 소유권·위임 권한은 업무 인가에서 별도로 검사한다.
 */
@Component
public final class JwtPrincipalConverter implements Converter<Jwt, AbstractAuthenticationToken> {
    private static final Logger log = LoggerFactory.getLogger(JwtPrincipalConverter.class);
    private final CustomerMapper customers;
    private final PartnerMapper partners;

    public JwtPrincipalConverter(CustomerMapper customers, PartnerMapper partners) {
        this.customers = customers;
        this.partners = partners;
    }

    /** 매 인증 요청에서 DB를 조회하며, 클라이언트가 주장하는 UUID 대신 DB가 가진 UUID를 사용한다. */
    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Object subject = jwt.getClaims().get("sub");
        Object typeClaim = jwt.getClaims().get("principal_type");
        // Decoder에서 검사한 값도 변환기의 입력 경계에서 다시 확인한다.
        if (!(subject instanceof String sub) || sub.isBlank() || !(typeClaim instanceof String type)) {
            throw invalidPrincipal();
        }
        try {
            PrincipalType principalType;
            UUID id;
            // 같은 sub가 양쪽 테이블에 있어도 유형에 맞는 테이블만 조회한다. 없으면 인증 실패다.
            switch (type) {
                case "CUSTOMER" -> {
                    principalType = PrincipalType.CUSTOMER;
                    id = customers.findByAuthSubject(sub).orElseThrow(JwtPrincipalConverter::invalidPrincipal).id();
                }
                case "PARTNER" -> {
                    principalType = PrincipalType.PARTNER;
                    id = partners.findByAuthSubject(sub).orElseThrow(JwtPrincipalConverter::invalidPrincipal).id();
                }
                default -> throw invalidPrincipal();
            }
            var principal = new AuthenticatedPrincipal(id, principalType);
            // JWT scope/roles를 사용하지 않고 검증한 주체 유형으로 서버에서 역할을 정한다.
            // 이 토큰 클래스는 인증 결과의 그릇으로만 사용한다. 여기서 비밀번호 로그인을 하는 것은 아니다.
            // credentials에는 JWT 원문을 남기지 않는다. 반환 후 Spring Security가 현재 요청의 인증 정보로 저장한다.
            return UsernamePasswordAuthenticationToken.authenticated(principal, null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + principalType.name())));
        } catch (DataAccessException error) {
            // DB 장애를 주체 미등록으로 취급하지 않는다. 오류 처리기가 이 코드를 받아 일반적인 503으로 응답한다.
            // JWT, sub, SQL, 예외 세부 정보를 로그에 남기지 않는다.
            log.error("Authentication identity store is unavailable");
            throw new OAuth2AuthenticationException(new OAuth2Error("temporarily_unavailable"));
        }
    }

    /** 미등록 주체·허용하지 않는 유형 등은 상세 DB 관계를 공개하지 않고 동일한 인증 실패로 처리한다. */
    private static OAuth2AuthenticationException invalidPrincipal() {
        return new OAuth2AuthenticationException(new OAuth2Error("invalid_token"));
    }
}
