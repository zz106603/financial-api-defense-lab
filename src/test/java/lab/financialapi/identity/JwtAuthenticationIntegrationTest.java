package lab.financialapi.identity;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import lab.financialapi.persistence.PostgresIntegrationSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;

import static lab.financialapi.identity.JwtTestTokens.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 실제 서명 Bearer → 보안 필터 → Decoder/Validator → Converter → PostgreSQL의 연결을 검증한다.
 * MockMvc는 HTTP 서버 대신 요청을 전달하지만 JWT 서명 검증이나 DB 조회를 모킹하지 않는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Import(JwtAuthenticationIntegrationTest.ProbeConfiguration.class)
@Sql("/fixtures/financial-data.sql")
@Transactional
class JwtAuthenticationIntegrationTest extends PostgresIntegrationSupport {
    private static final String PROBE = "/api/v1/__identity_test__";
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    MockMvc mvc;

    @DynamicPropertySource
    static void jwtProperties(DynamicPropertyRegistry registry) {
        registerProperties(registry);
    }

    @BeforeEach
    void configureMvc() {
        // 제품의 필터 체인을 적용한다. jwt() 등으로 인증 성공 상태를 미리 주입하지 않는다.
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void 토큰이_없으면_금융_API_경로를_401로_거부한다() throws Exception {
        mvc.perform(get("/api/v1/accounts/30000000-0000-4000-8000-000000000001"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void 실제_서명된_고객_JWT를_DB_UUID와_서버_역할로_변환한다() throws Exception {
        mvc.perform(get(PROBE).header(HttpHeaders.AUTHORIZATION, "Bearer " + token("customer-a", "CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("10000000-0000-4000-8000-000000000001"))
                .andExpect(jsonPath("$.authorities", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$.authorities[0]").value("ROLE_CUSTOMER"))
                .andExpect(authenticated().withAuthentication(authentication -> {
                    assertThat(authentication.getPrincipal()).extracting("id", "type")
                            .containsExactly(UUID.fromString("10000000-0000-4000-8000-000000000001"), PrincipalType.CUSTOMER);
                    assertThat(authentication.getCredentials()).isNull();
                }));
    }

    @Test
    void 실제_서명된_제휴사_JWT는_제휴사_DB_UUID로_식별한다() throws Exception {
        mvc.perform(get(PROBE).header(HttpHeaders.AUTHORIZATION, "Bearer " + token("partner-x", "PARTNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("20000000-0000-4000-8000-000000000001"))
                .andExpect(jsonPath("$.authorities", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$.authorities[0]").value("ROLE_PARTNER"));
    }

    @Test
    void 추가_ID와_scope와_roles_클레임이나_요청_파라미터로_주체를_바꿀_수_없다() throws Exception {
        var claims = claims("customer-a", "CUSTOMER");
        claims.put("customer_id", "10000000-0000-4000-8000-000000000002");
        claims.put("principal_id", "10000000-0000-4000-8000-000000000002");
        claims.put("scope", "ACCOUNT_READ TRANSACTION_READ ADMIN");
        claims.put("roles", List.of("ADMIN", "PARTNER"));
        mvc.perform(get(PROBE).param("customerId", "10000000-0000-4000-8000-000000000002")
                        .header("X-Customer-Id", "10000000-0000-4000-8000-000000000002")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + sign(claims)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("10000000-0000-4000-8000-000000000001"))
                .andExpect(jsonPath("$.authorities", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$.authorities[0]").value("ROLE_CUSTOMER"));
    }

    @Test
    void 동일한_sub가_양쪽_테이블에_있어도_유형별로_매핑한다() throws Exception {
        jdbc.update("INSERT INTO customer VALUES (?, 'partner-x', '동일 sub 합성 고객', now())",
                UUID.fromString("10000000-0000-4000-8000-000000000003"));
        mvc.perform(get(PROBE).header(HttpHeaders.AUTHORIZATION, "Bearer " + token("partner-x", "PARTNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("20000000-0000-4000-8000-000000000001"));
        mvc.perform(get(PROBE).header(HttpHeaders.AUTHORIZATION, "Bearer " + token("partner-x", "CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("10000000-0000-4000-8000-000000000003"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidTokens")
    void 잘못된_서명과_클레임은_상세정보_없이_401로_거부한다(String reason, String jwt) throws Exception {
        var result = mvc.perform(get(PROBE).header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(jwt, "SELECT", "Exception", ISSUER);
    }

    static Stream<Arguments> invalidTokens() throws Exception {
        var cases = Stream.<Arguments>builder();
        for (String claim : List.of("iss", "aud", "exp", "sub", "principal_type")) {
            var claims = claims("customer-a", "CUSTOMER");
            claims.remove(claim);
            cases.add(Arguments.of(claim + " 누락", sign(claims)));
        }
        var values = Map.<String, Object>of(
                "iss", "https://wrong-issuer.invalid", "aud", List.of("wrong-audience"),
                "exp", Date.from(Instant.now().minusSeconds(120)),
                "nbf", Date.from(Instant.now().plusSeconds(120)), "sub", " ", "principal_type", "ANONYMOUS");
        values.forEach((claim, value) -> {
            var claims = claims("customer-a", "CUSTOMER");
            claims.put(claim, value);
            cases.add(Arguments.of(claim + " 잘못된 값", sign(claims)));
        });
        for (Object type : List.of("ADMIN", "customer", 7, List.of("CUSTOMER"))) {
            var claims = claims("customer-a", "CUSTOMER");
            claims.put("principal_type", type);
            cases.add(Arguments.of("주체 유형 " + type, sign(claims)));
        }
        cases.add(Arguments.of("미등록 고객", token("missing-customer", "CUSTOMER")));
        cases.add(Arguments.of("미등록 제휴사", token("missing-partner", "PARTNER")));
        cases.add(Arguments.of("고객 sub를 제휴사로 사용", token("customer-a", "PARTNER")));
        cases.add(Arguments.of("제휴사 sub를 고객으로 사용", token("partner-x", "CUSTOMER")));
        cases.add(Arguments.of("다른 RSA 키", sign(claims("customer-a", "CUSTOMER"), generateKeys(), JWSAlgorithm.RS256)));
        cases.add(Arguments.of("허용하지 않은 RS512", sign(claims("customer-a", "CUSTOMER"), KEYS, JWSAlgorithm.RS512)));
        cases.add(Arguments.of("서명 없음", new PlainJWT(new JWTClaimsSet.Builder().subject("customer-a").build()).serialize()));
        cases.add(Arguments.of("잘못된 토큰 형식", "not-a-jwt"));
        String original = token("customer-a", "CUSTOMER");
        String[] pieces = original.split("\\.");
        pieces[2] = (pieces[2].startsWith("A") ? "B" : "A") + pieces[2].substring(1);
        cases.add(Arguments.of("서명 변조", String.join(".", pieces)));
        var hmacClaims = new JWTClaimsSet.Builder();
        claims("customer-a", "CUSTOMER").forEach(hmacClaims::claim);
        var hmac = new com.nimbusds.jwt.SignedJWT(new com.nimbusds.jose.JWSHeader(JWSAlgorithm.HS256), hmacClaims.build());
        hmac.sign(new com.nimbusds.jose.crypto.MACSigner(KEYS.getPublic().getEncoded()));
        cases.add(Arguments.of("RSA 공개키를 HMAC 비밀키로 사용", hmac.serialize()));
        for (String claim : List.of("sub", "aud", "exp", "nbf")) {
            var claims = claims("customer-a", "CUSTOMER");
            claims.put(claim, "aud".equals(claim) ? 42 : List.of("invalid-type"));
            cases.add(Arguments.of(claim + " 잘못된 자료형", sign(claims)));
        }
        var expired = claims("customer-a", "CUSTOMER");
        expired.put("exp", Date.from(Instant.now().minusSeconds(1)));
        cases.add(Arguments.of("만료 유예 시간 없음", sign(expired)));
        var future = claims("customer-a", "CUSTOMER");
        future.put("nbf", Date.from(Instant.now().plusSeconds(30)));
        cases.add(Arguments.of("활성화 유예 시간 없음", sign(future)));
        return cases.build();
    }

    @Test
    void nbf가_없고_audience가_문자열인_유효한_토큰도_검증한다() throws Exception {
        var claims = claims("customer-a", "CUSTOMER");
        claims.remove("nbf");
        claims.put("aud", AUDIENCE);
        mvc.perform(get(PROBE).header(HttpHeaders.AUTHORIZATION, "Bearer " + sign(claims)))
                .andExpect(status().isOk());
    }

    @Test
    void 쿼리와_폼의_access_token은_인증에_사용하지_않는다() throws Exception {
        mvc.perform(get(PROBE).param("access_token", token("customer-a", "CUSTOMER")))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(PROBE).contentType("application/x-www-form-urlencoded")
                        .param("access_token", token("customer-a", "CUSTOMER")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 인증_이후_새_요청도_Bearer를_요구하고_세션을_생성하지_않는다() throws Exception {
        var result = mvc.perform(post(PROBE).header(HttpHeaders.AUTHORIZATION, "Bearer " + token("customer-a", "CUSTOMER")))
                .andExpect(status().isOk()).andReturn();
        assertThat(result.getRequest().getSession(false)).isNull();
        assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
        mvc.perform(get(PROBE)).andExpect(status().isUnauthorized());
    }

    @Test
    void 정상_토큰도_새_요청에서_DB_주체를_다시_확인한다() throws Exception {
        String jwt = token("customer-a", "CUSTOMER");
        mvc.perform(get(PROBE).header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt)).andExpect(status().isOk());
        jdbc.update("UPDATE customer SET auth_subject = 'disabled-customer-a' WHERE auth_subject = 'customer-a'");
        mvc.perform(get(PROBE).header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt)).andExpect(status().isUnauthorized());
    }

    @Test
    void Basic_인증은_Bearer_인증을_대체할_수_없다() throws Exception {
        String basic = java.util.Base64.getEncoder().encodeToString(
                "customer-a:password".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        mvc.perform(get(PROBE).header(HttpHeaders.AUTHORIZATION, "Basic " + basic))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
    }

    @Test
    void 제휴사는_인증되어도_고객_역할이_필요한_작업은_403으로_거부한다() throws Exception {
        mvc.perform(get(PROBE + "/customer").header(HttpHeaders.AUTHORIZATION, "Bearer " + token("partner-x", "PARTNER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mvc.perform(get(PROBE + "/customer").header(HttpHeaders.AUTHORIZATION, "Bearer " + token("customer-a", "CUSTOMER")))
                .andExpect(status().isOk());
    }

    @Test
    void 오류_추적_ID는_클라이언트_값을_신뢰하지_않고_본문과_헤더가_일치한다() throws Exception {
        var result = mvc.perform(get(PROBE).header("X-Request-Id", "client-controlled"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.requestId").value(org.hamcrest.Matchers.not("client-controlled")))
                .andReturn();
        String id = result.getResponse().getHeader("X-Request-Id");
        assertThat(UUID.fromString(id)).isNotNull();
        assertThat(result.getResponse().getContentAsString()).contains(id);
    }

    @Test
    void 주체_DB_조회가_실패하면_데이터를_허용하지_않고_일반적인_503을_반환한다() throws Exception {
        // 테스트 트랜잭션에서만 테이블을 숨기고 테스트 종료 시 DDL도 롤백한다.
        jdbc.execute("ALTER TABLE customer RENAME TO unavailable_customer");
        var result = mvc.perform(get(PROBE).header(HttpHeaders.AUTHORIZATION, "Bearer " + token("customer-a", "CUSTOMER")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE")).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("unavailable_customer", "SELECT", "Exception");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {
        // 인증된 주체를 확인할 Controller는 이 테스트에서만 등록한다. 제품에 진단 API를 추가하지 않는다.
        @Bean ProbeController probeController() { return new ProbeController(); }
    }

    @RestController
    @TestComponent
    static class ProbeController {
        @RequestMapping(value = PROBE, method = {RequestMethod.GET, RequestMethod.POST})
        public Map<String, Object> identity(@AuthenticationPrincipal AuthenticatedPrincipal principal,
                                           Authentication authentication) {
            return Map.of("id", principal.id().toString(), "authorities",
                    authentication.getAuthorities().stream().map(Object::toString).toList());
        }

        @GetMapping(PROBE + "/customer")
        @PreAuthorize("hasRole('CUSTOMER')")
        public Map<String, String> customer() { return Map.of("result", "ok"); }
    }
}
