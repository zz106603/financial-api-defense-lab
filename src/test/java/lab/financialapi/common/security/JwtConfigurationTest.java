package lab.financialapi.common.security;

import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 신뢰 기준이 없거나 공개키가 잘못된 상태로 Decoder를 만들지 못하는지 확인한다. */
class JwtConfigurationTest {
    @ParameterizedTest
    @MethodSource("invalidSettings")
    void 필수_설정이_없으면_인증_구성을_만들지_않는다(String issuer, String audience, Resource key) {
        assertThatThrownBy(() -> new JwtProperties(issuer, audience, key)).isInstanceOf(IllegalArgumentException.class);
    }

    static Stream<Arguments> invalidSettings() {
        var key = new ByteArrayResource(new byte[0]);
        return Stream.of(Arguments.of(null, "api", key), Arguments.of(" ", "api", key),
                Arguments.of("issuer", null, key), Arguments.of("issuer", " ", key),
                Arguments.of("issuer", "api", null));
    }

    @Test
    void 유효하지_않은_PEM은_Decoder를_생성할_수_없다() {
        var properties = new JwtProperties("issuer", "api", new ByteArrayResource(new byte[0]));
        assertThatThrownBy(() -> new SecurityConfiguration().jwtDecoder(properties))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 존재하지_않는_공개키_파일로_기동할_수_없다(@TempDir Path directory) {
        var properties = new JwtProperties("issuer", "api", new FileSystemResource(directory.resolve("missing.pem")));
        assertThatThrownBy(() -> new SecurityConfiguration().jwtDecoder(properties))
                .isInstanceOf(java.io.IOException.class);
    }

    @Test
    void RSA_2048비트보다_작은_공개키는_거부한다() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(1024);
        String pem = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getEncoder().encodeToString(generator.generateKeyPair().getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----";
        var properties = new JwtProperties("issuer", "api",
                new ByteArrayResource(pem.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        assertThatThrownBy(() -> new SecurityConfiguration().jwtDecoder(properties))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("2048");
    }
}
