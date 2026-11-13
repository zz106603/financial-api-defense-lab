package lab.financialapi.audit;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResourceReferenceGeneratorTest {
    private static final String KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

    @Test
    void 같은_키와_계좌는_같은_가명이고_계좌나_키가_달라지면_가명도_달라진다() {
        var first = new ResourceReferenceGenerator(KEY);
        var second = new ResourceReferenceGenerator("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        UUID account = UUID.fromString("30000000-0000-4000-8000-000000000001");
        String ref = first.accountReference(account);

        assertThat(ref).matches("account:v1:[0-9a-f]{64}")
                .doesNotContain(account.toString());
        assertThat(new ResourceReferenceGenerator(KEY).accountReference(account)).isEqualTo(ref);
        assertThat(first.accountReference(UUID.fromString("30000000-0000-4000-8000-000000000002"))).isNotEqualTo(ref);
        assertThat(second.accountReference(account)).isNotEqualTo(ref);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-base64!", "c2hvcnQ="})
    void 키가_없거나_잘못된_Base64이거나_짧으면_구성을_거부한다(String key) {
        assertThatThrownBy(() -> new ResourceReferenceGenerator(key)).isInstanceOf(IllegalArgumentException.class);
    }
}
