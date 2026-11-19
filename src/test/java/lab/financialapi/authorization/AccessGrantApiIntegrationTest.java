package lab.financialapi.authorization;

import java.util.UUID;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.stream.Stream;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(OutputCaptureExtension.class)
class AccessGrantApiIntegrationTest extends GrantApiSupport {
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void 본인_계좌_부여는_201과_공개_필드만_반환하고_검증된_주체로_감사한다() throws Exception {
        var response = grant("customer-a", "CUSTOMER", A1, body(Y, "ACCOUNT_READ"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.*", hasSize(5)))
                .andExpect(jsonPath("$.accountId").value(A1))
                .andExpect(jsonPath("$.partnerId").value(Y))
                .andExpect(jsonPath("$.permission").value("ACCOUNT_READ"))
                .andExpect(jsonPath("$.grantedBy").doesNotExist())
                .andReturn().getResponse();
        UUID.fromString(JsonPath.read(response.getContentAsString(), "$.grantId"));
        assertThat(JsonPath.<String>read(response.getContentAsString(), "$.grantedAt")).endsWith("Z");
        var event = jdbc.queryForMap("SELECT * FROM audit_event");
        assertThat(event.get("principal_id")).isEqualTo(UUID.fromString("10000000-0000-4000-8000-000000000001"));
        assertThat(event.get("action")).isEqualTo("GRANT_CREATE");
        assertThat(event.get("reason_code")).isEqualTo("GRANT_CREATED");
        assertThat(event.get("resource_ref").toString()).matches("account:v1:[0-9a-f]{64}");
        assertThat(event.get("request_id")).isEqualTo(response.getHeader("X-Request-Id"));
        assertThat(event.get("request_id")).isNotEqualTo("untrusted-client-id");
        assertThat(event.toString()).doesNotContain(A1, "Bearer", "customer-a", "1500000", "100-***-001");
    }

    @Test
    void 독립_권한을_부여하고_같은_활성_권한만_409로_거부한다() throws Exception {
        grant("customer-a", "CUSTOMER", A1, body(X, "TRANSACTION_READ")).andExpect(status().isCreated());
        grant("customer-a", "CUSTOMER", A1, body(X, "ACCOUNT_READ"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ACTIVE_GRANT_CONFLICT"));
        assertThat(count("SELECT count(*) FROM access_grant WHERE revoked_at IS NULL")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM audit_event WHERE reason_code = 'ACTIVE_GRANT_CONFLICT' AND result = 'DENIED'"))
                .isEqualTo(1);
    }

    @ParameterizedTest
    @MethodSource("notFoundGrants")
    void 타인_미존재_계좌와_미존재_제휴사_부여는_동일한_404다(String account, String partner) throws Exception {
        var response = grant("customer-a", "CUSTOMER", account, body(partner, "ACCOUNT_READ"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain(account, partner, "SQL", "Exception");
        assertThat(count("SELECT count(*) FROM access_grant")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit_event WHERE result = 'DENIED'")).isEqualTo(1);
    }

    static Stream<Arguments> notFoundGrants() {
        return Stream.of(Arguments.of(B1, Y), Arguments.of(MISSING, Y), Arguments.of(A1, MISSING));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "{", "{\"partnerId\":null,\"permission\":\"ACCOUNT_READ\"}",
            "{\"partnerId\":\"1-1-1-1-1\",\"permission\":\"ACCOUNT_READ\"}",
            "{\"partnerId\":\"20000000-0000-4000-8000-000000000002\",\"permission\":null}",
            "{\"partnerId\":\"20000000-0000-4000-8000-000000000002\",\"permission\":0}",
            "{\"partnerId\":\"20000000-0000-4000-8000-000000000002\",\"permission\":\"ADMIN\"}"})
    void 잘못된_본문은_400이며_권한을_만들지_않는다(String body) throws Exception {
        grant("customer-a", "CUSTOMER", A1, body).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        assertThat(count("SELECT count(*) FROM access_grant")).isEqualTo(1);
    }

    @ParameterizedTest
    @MethodSource("encodedPartnerIds")
    void 표준_36자_UUID가_아닌_Base64_제휴사_ID는_400이다(String encoded) throws Exception {
        grant("customer-a", "CUSTOMER", A1, body(encoded, "ACCOUNT_READ"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        assertThat(count("SELECT count(*) FROM access_grant")).isEqualTo(1);
    }

    static Stream<String> encodedPartnerIds() {
        UUID partner = UUID.fromString(Y);
        byte[] bytes = ByteBuffer.allocate(16)
                .putLong(partner.getMostSignificantBits()).putLong(partner.getLeastSignificantBits()).array();
        return Stream.of(Base64.getEncoder().encodeToString(bytes),
                Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
    }

    @Test
    void 잘못된_경로_UUID는_400이다() throws Exception {
        grant("customer-a", "CUSTOMER", "1-1-1-1-1", body(Y, "ACCOUNT_READ")).andExpect(status().isBadRequest());
        revoke("customer-a", "CUSTOMER", A1, "invalid").andExpect(status().isBadRequest());
    }

    @Test
    void 미인증_권한_변경은_401이다() throws Exception {
        mvc.perform(post("/api/v1/accounts/" + A1 + "/grants").contentType(MediaType.APPLICATION_JSON)
                .content(body(Y, "ACCOUNT_READ"))).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/v1/accounts/" + A1 + "/grants/" + INITIAL)).andExpect(status().isUnauthorized());
    }

    @Test
    void 제휴사의_부여와_회수는_403이며_동일_요청_ID로_독립_감사한다() throws Exception {
        var post = grant("partner-x", "PARTNER", A1, body(Y, "ACCOUNT_READ"))
                .andExpect(status().isForbidden()).andReturn().getResponse();
        var delete = revoke("partner-x", "PARTNER", A1, INITIAL)
                .andExpect(status().isForbidden()).andReturn().getResponse();
        assertThat(jdbc.queryForList("SELECT request_id FROM audit_event", String.class))
                .containsExactlyInAnyOrder(post.getHeader("X-Request-Id"), delete.getHeader("X-Request-Id"));
        assertThat(count("SELECT count(*) FROM audit_event WHERE reason_code = 'PRINCIPAL_TYPE_DENIED' AND result = 'DENIED'"))
                .isEqualTo(2);
        assertThat(revokedAt(INITIAL)).isNull();
    }

    @Test
    void 감사_INSERT_실패는_부여를_롤백하고_503이다() throws Exception {
        withUnavailableAudit(() -> grant("customer-a", "CUSTOMER", A1, body(Y, "ACCOUNT_READ"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE")));
        assertThat(count("SELECT count(*) FROM access_grant")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit_event")).isZero();
    }

    @Test
    void 제휴사_거부_감사_실패도_POST와_DELETE_모두_503이다() throws Exception {
        withUnavailableAudit(() -> {
            grant("partner-x", "PARTNER", A1, body(Y, "ACCOUNT_READ")).andExpect(status().isServiceUnavailable());
            revoke("partner-x", "PARTNER", A1, INITIAL).andExpect(status().isServiceUnavailable());
        });
        assertThat(revokedAt(INITIAL)).isNull();
    }

    @Test
    void 다른_무결성_오류를_활성_권한_중복_409로_숨기지_않는다() throws Exception {
        jdbc.execute("ALTER TABLE access_grant ADD CONSTRAINT test_reject_grant CHECK (partner_id <> '" + Y + "')");
        try {
            grant("customer-a", "CUSTOMER", A1, body(Y, "ACCOUNT_READ"))
                    .andExpect(status().isServiceUnavailable());
        } finally {
            jdbc.execute("ALTER TABLE access_grant DROP CONSTRAINT test_reject_grant");
        }
        assertThat(count("SELECT count(*) FROM access_grant")).isEqualTo(1);
    }

    @Test
    void 접근_거부와_중복_감사는_호출측_업무_롤백에도_유지된다() {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            try {
                grant("customer-a", "CUSTOMER", B1, body(Y, "ACCOUNT_READ")).andExpect(status().isNotFound());
                grant("customer-a", "CUSTOMER", A1, body(X, "ACCOUNT_READ")).andExpect(status().isConflict());
            } catch (Exception error) {
                throw new AssertionError(error);
            }
            tx.setRollbackOnly();
        });
        assertThat(count("SELECT count(*) FROM audit_event WHERE result = 'DENIED'")).isEqualTo(2);
    }

    @Test
    void 최초_회수와_반복_회수는_204이며_회수_시각과_감사_사유를_보존한다() throws Exception {
        revoke("customer-a", "CUSTOMER", A1, INITIAL).andExpect(status().isNoContent())
                .andExpect(content().string(""));
        Object first = revokedAt(INITIAL);
        assertThat(first).isNotNull();
        revoke("customer-a", "CUSTOMER", A1, INITIAL).andExpect(status().isNoContent());
        assertThat(revokedAt(INITIAL)).isEqualTo(first);
        assertThat(jdbc.queryForList("SELECT reason_code FROM audit_event ORDER BY occurred_at", String.class))
                .containsExactly("GRANT_REVOKED", "GRANT_ALREADY_REVOKED");
        assertThat(count("SELECT count(*) FROM access_grant")).isEqualTo(1);
    }

    @ParameterizedTest
    @MethodSource("notFoundRevokes")
    void 타인_계좌_불일치와_미존재_회수는_204가_아닌_404다(String sub, String account, String grant) throws Exception {
        revoke(sub, "CUSTOMER", account, grant).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        assertThat(revokedAt(INITIAL)).isNull();
        assertThat(count("SELECT count(*) FROM audit_event WHERE result = 'DENIED'")).isEqualTo(1);
    }

    static Stream<Arguments> notFoundRevokes() {
        return Stream.of(Arguments.of("customer-b", A1, INITIAL), Arguments.of("customer-a", A2, INITIAL),
                Arguments.of("customer-a", MISSING, INITIAL), Arguments.of("customer-a", A1, MISSING));
    }

    @Test
    void 최초_회수_감사_실패는_회수를_롤백하고_반복_감사_실패도_503이다() throws Exception {
        withUnavailableAudit(() -> revoke("customer-a", "CUSTOMER", A1, INITIAL)
                .andExpect(status().isServiceUnavailable()));
        assertThat(revokedAt(INITIAL)).isNull();
        assertThat(count("SELECT count(*) FROM audit_event")).isZero();
        revoke("customer-a", "CUSTOMER", A1, INITIAL).andExpect(status().isNoContent());
        Object first = revokedAt(INITIAL);
        withUnavailableAudit(() -> revoke("customer-a", "CUSTOMER", A1, INITIAL)
                .andExpect(status().isServiceUnavailable()));
        assertThat(revokedAt(INITIAL)).isEqualTo(first);
        assertThat(count("SELECT count(*) FROM audit_event")).isEqualTo(1);
    }

    @Test
    void 재부여는_새_ID이며_이전_ID의_반복_회수는_새_권한과_다른_조회_권한을_유지한다() throws Exception {
        String transaction = JsonPath.read(grant("customer-a", "CUSTOMER", A1, body(X, "TRANSACTION_READ"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.grantId");
        revoke("customer-a", "CUSTOMER", A1, INITIAL).andExpect(status().isNoContent());
        String replacement = JsonPath.read(grant("customer-a", "CUSTOMER", A1, body(X, "ACCOUNT_READ"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.grantId");
        assertThat(replacement).isNotEqualTo(INITIAL);
        revoke("customer-a", "CUSTOMER", A1, INITIAL).andExpect(status().isNoContent());
        assertThat(revokedAt(replacement)).isNull();
        assertThat(revokedAt(transaction)).isNull();
        revoke("customer-a", "CUSTOMER", A1, transaction).andExpect(status().isNoContent());
        assertThat(revokedAt(replacement)).isNull();
    }

    @Test
    void 추가_고객_ID_클레임과_본문_부여자로_타인_계좌를_변경하지_못한다() throws Exception {
        var claims = lab.financialapi.identity.JwtTestTokens.claims("customer-b", "CUSTOMER");
        claims.put("customer_id", "10000000-0000-4000-8000-000000000001");
        claims.put("roles", "CUSTOMER");
        String jwt = lab.financialapi.identity.JwtTestTokens.sign(claims);
        mvc.perform(post("/api/v1/accounts/" + A1 + "/grants")
                        .header("Authorization", "Bearer " + jwt)
                        .header("X-Customer-Id", "10000000-0000-4000-8000-000000000001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Y, "ACCOUNT_READ").replace("}",
                                ",\"grantedBy\":\"10000000-0000-4000-8000-000000000001\"}")))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/accounts/" + A1 + "/grants/" + INITIAL)
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isNotFound());
        assertThat(revokedAt(INITIAL)).isNull();
        assertThat(count("SELECT count(*) FROM access_grant")).isEqualTo(1);
    }

    @Test
    void 감사_커밋_시점_실패도_성공_응답_없이_부여와_회수를_롤백한다(CapturedOutput output) throws Exception {
        jdbc.execute("CREATE FUNCTION test_fail_deferred_audit() RETURNS trigger LANGUAGE plpgsql AS $$ "
                + "BEGIN RAISE EXCEPTION 'synthetic deferred audit failure'; RETURN NEW; END $$");
        jdbc.execute("CREATE CONSTRAINT TRIGGER test_fail_deferred_audit AFTER INSERT ON audit_event "
                + "DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION test_fail_deferred_audit()");
        try {
            var response = grant("customer-a", "CUSTOMER", A1, body(Y, "ACCOUNT_READ"))
                    .andExpect(status().isServiceUnavailable()).andReturn().getResponse().getContentAsString();
            assertThat(response).doesNotContain("SQL", "Exception", "synthetic", "grantId", "grantedAt");
            revoke("customer-a", "CUSTOMER", A1, INITIAL).andExpect(status().isServiceUnavailable());
            assertThat(revokedAt(INITIAL)).isNull();
            assertThat(count("SELECT count(*) FROM access_grant")).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM audit_event")).isZero();
            assertThat(output.toString()).doesNotContain("synthetic deferred audit failure",
                    "INSERT INTO access_grant", "INSERT INTO audit_event", "Bearer ");
        } finally {
            jdbc.execute("DROP TRIGGER test_fail_deferred_audit ON audit_event");
            jdbc.execute("DROP FUNCTION test_fail_deferred_audit()");
        }
    }

    @Test
    void 객체_거부와_중복_거부도_필수_감사_실패면_503이다() throws Exception {
        withUnavailableAudit(() -> {
            grant("customer-a", "CUSTOMER", B1, body(Y, "ACCOUNT_READ")).andExpect(status().isServiceUnavailable());
            grant("customer-a", "CUSTOMER", A1, body(X, "ACCOUNT_READ")).andExpect(status().isServiceUnavailable());
            revoke("customer-b", "CUSTOMER", A1, INITIAL).andExpect(status().isServiceUnavailable());
        });
        assertThat(revokedAt(INITIAL)).isNull();
    }

    void withUnavailableAudit(CheckedAction action) throws Exception {
        jdbc.execute("ALTER TABLE audit_event RENAME TO unavailable_grant_audit");
        try {
            action.run();
        } finally {
            jdbc.execute("ALTER TABLE unavailable_grant_audit RENAME TO audit_event");
        }
    }

    interface CheckedAction {
        void run() throws Exception;
    }
}
