package lab.financialapi.audit;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

/**
 * 감사에서 같은 계좌의 반복 접근을 연결할 수 있도록 안정적인 HMAC 가명 참조를 만든다.
 * 원래 UUID를 저장하는 방식이나 복호화하는 암호화 방식이 아니며, 외부 비밀키가 있어야 값을 계산할 수 있다.
 * 같은 키/계좌는 같은 가명이고 키를 바꾸면 이전 감사의 가명과 연결되지 않는다.
 */
@Component
public final class ResourceReferenceGenerator {
    private final SecretKeySpec key;

    /** 기동 시 외부 키를 검사한다. JWT 서명 개인키와 별개이며 제품에 기본 비밀키를 두지 않는다. */
    public ResourceReferenceGenerator(@Value("${audit.resource-hmac-key:}") String base64Key) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64Key);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("audit.resource-hmac-key must be valid Base64");
        }

        Assert.isTrue(decoded.length >= 32, "audit.resource-hmac-key must contain at least 32 bytes");
        this.key = new SecretKeySpec(decoded, "HmacSHA256");
    }

    /** 계좌 UUID의 표준 문자열을 HMAC-SHA256으로 처리하고 종류/형식 버전을 붙여 감사에 전달한다. */
    public String accountReference(UUID accountId) {
        try {
            // Mac은 변경 가능한 객체이므로 각 호출에서 생성해 동시 요청 사이의 상태를 공유하지 않는다.
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(key);

            // 입력에도 대상 종류를 붙여, 향후 다른 자원의 같은 UUID와 구별할 수 있게 한다.
            byte[] input = ("account:" + accountId).getBytes(StandardCharsets.UTF_8);
            return "account:v1:" + HexFormat.of().formatHex(mac.doFinal(input));
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("Audit resource reference generation failed");
        }
    }
}
