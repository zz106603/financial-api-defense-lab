package lab.financialapi.common.security;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

import jakarta.servlet.DispatcherType;
import lab.financialapi.identity.JwtPrincipalConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.MappedJwtClaimSetConverter;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.Assert;

/**
 * 웹 서버 시작 시 JWT 검증기와 보안 필터 체인을 조립한다. 토큰을 발급하는 클래스는 아니다.
 * 요청이 들어오면 Spring Security가 이 구성에 따라 Decoder → Validator → Converter를 실행한다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(JwtProperties.class)
@EnableMethodSecurity
public class SecurityConfiguration {
    /** 신뢰하는 공개키로 서명과 클레임을 검증할 Decoder를 서버 시작 시 한 번 생성한다. */
    @Bean
    JwtDecoder jwtDecoder(JwtProperties properties) throws IOException {
        // 발급 도구가 가진 개인키의 짝인 공개키만 읽는다. 잘못된 키로는 서버를 시작하지 않는다.
        try (var input = properties.publicKeyLocation().getInputStream()) {
            var key = RsaKeyConverters.x509().convert(input);
            Assert.notNull(key, "JWT RSA public key is required");
            Assert.isTrue(key.getModulus().bitLength() >= 2048, "JWT RSA key must be at least 2048 bits");
            // 토큰 헤더가 다른 알고리즘을 주장하더라도 서버는 RS256만 허용한다.
            var decoder = NimbusJwtDecoder.withPublicKey(key).signatureAlgorithm(SignatureAlgorithm.RS256).build();
            // sub를 문자열로 자동 보정하지 않고 원래 자료형을 유지해 Validator에서 검사한다.
            decoder.setClaimSetConverter(MappedJwtClaimSetConverter.withDefaults(
                    Map.<String, Converter<Object, ?>>of("sub", value -> value)));
            // 모두 통과해야 인증을 계속한다. 시각/발급자는 Spring 검증기, 추가 필수 조건은 자체 검증기다.
            // ZERO는 만료·활성화 시각의 유예가 없다는 뜻이다. exp 누락은 자체 검증기에서 거부한다.
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                    new JwtTimestampValidator(Duration.ZERO), new JwtIssuerValidator(properties.issuer()),
                    new RequiredJwtClaimsValidator(properties.audience())));
            return decoder;
        }
    }

    /** Controller보다 먼저 요청을 검사할 필터들을 연결한다. 아래 설정 호출 순서가 필터 실행 순서는 아니다. */
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtDecoder decoder, JwtPrincipalConverter converter)
            throws Exception {
        var errors = new SecurityErrorHandler();
        return http
                // 쿠키/세션으로 자동 인증하지 않는 Bearer 전용 API이므로 CSRF 검사를 사용하지 않는다.
                .csrf(AbstractHttpConfigurer::disable)
                // 아이디·비밀번호 로그인과 로그인 화면을 제공하지 않는다.
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                // 이전 요청의 인증을 세션에서 복원하지 않는다. 매 요청마다 Bearer 토큰이 필요하다.
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        // 컨테이너의 오류 응답 전달만 허용한다. 외부에서 직접 /error를 호출하면 인증 대상이다.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        // 이는 신원 확인 조건이다. 계좌 소유권·위임 범위는 이후 업무 인가에서 검사해야 한다.
                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(errors).accessDeniedHandler(errors))
                // Bearer를 읽은 Resource Server가 먼저 JWT를 검증하고, 성공한 JWT만 DB 주체로 변환한다.
                .oauth2ResourceServer(oauth -> oauth.authenticationEntryPoint(errors).accessDeniedHandler(errors)
                        .jwt(jwt -> jwt.decoder(decoder).jwtAuthenticationConverter(converter)))
                .build();
    }
}
