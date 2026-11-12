package lab.financialapi.identity;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * 자동 테스트가 실제 서명 JWT를 보내기 위한 Fixture다. 배포 JAR이나 제품 토큰 발급 API에는 포함되지 않는다.
 * 테스트 JVM 안에서만 개인키를 생성하고 디스크에는 공개키만 쓴다.
 * 서명에 쓴 개인키와 Decoder가 읽는 공개키가 한 쌍이므로 정상·위조 토큰을 실제 암호 검증으로 구분할 수 있다.
 */
public final class JwtTestTokens {
    public static final String ISSUER = "https://issuer.test.invalid";
    public static final String AUDIENCE = "financial-api-test";
    static final KeyPair KEYS = generateKeys();
    static final String PUBLIC_KEY_LOCATION = writePublicKey();

    private JwtTestTokens() { }

    public static void registerProperties(DynamicPropertyRegistry registry) {
        // 테스트 Decoder에는 이 공개키만 신뢰하도록 설정한다. 실제 환경의 키·발급자와 섞지 않는다.
        registry.add("security.jwt.issuer", () -> ISSUER);
        registry.add("security.jwt.audience", () -> AUDIENCE);
        registry.add("security.jwt.public-key-location", () -> PUBLIC_KEY_LOCATION);
    }

    /** 정상 토큰의 기본 클레임을 만든다. 거부 테스트는 필요한 값만 바꿔 잘못된 요청을 재현한다. */
    public static Map<String, Object> claims(String subject, String type) {
        var claims = new HashMap<String, Object>();
        claims.put("iss", ISSUER);
        claims.put("aud", List.of(AUDIENCE));
        claims.put("sub", subject);
        claims.put("principal_type", type);
        claims.put("nbf", Date.from(Instant.now().minusSeconds(60)));
        claims.put("exp", Date.from(Instant.now().plusSeconds(300)));
        return claims;
    }

    public static String token(String subject, String type) {
        return sign(claims(subject, type));
    }

    /** 실제 RSA 개인키로 RS256 서명한다. JWT 검증기를 우회하는 모의 인증 객체를 만드는 메서드가 아니다. */
    public static String sign(Map<String, Object> claims) {
        return sign(claims, KEYS, JWSAlgorithm.RS256);
    }

    static String sign(Map<String, Object> claims, KeyPair keys, JWSAlgorithm algorithm) {
        // 다른 키나 RS512를 넘기는 사례도 만들 수 있어, 서버의 신뢰 키·알고리즘 제한을 검증한다.
        try {
            var builder = new JWTClaimsSet.Builder();
            claims.forEach(builder::claim);
            var jwt = new SignedJWT(new JWSHeader(algorithm), builder.build());
            jwt.sign(new RSASSASigner((RSAPrivateKey) keys.getPrivate()));
            return jwt.serialize();
        } catch (Exception error) {
            throw new AssertionError("테스트 토큰 서명 실패", error);
        }
    }

    static KeyPair generateKeys() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private static String writePublicKey() {
        try {
            // Decoder는 실제 PEM 파일을 읽는다. 개인키는 이 파일에 쓰지 않고 JVM 메모리에만 둔다.
            var path = Files.createTempFile("financial-api-test-public-", ".pem");
            var body = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(KEYS.getPublic().getEncoded());
            Files.writeString(path, "-----BEGIN PUBLIC KEY-----\n" + body + "\n-----END PUBLIC KEY-----\n",
                    StandardCharsets.US_ASCII);
            path.toFile().deleteOnExit();
            return path.toUri().toString();
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }
}
