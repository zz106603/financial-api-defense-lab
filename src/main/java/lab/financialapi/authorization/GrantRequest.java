package lab.financialapi.authorization;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lab.financialapi.common.web.ApiException;
import tools.jackson.databind.JsonNode;

/** 부여자는 요청 본문이 아니라 검증된 인증 주체에서 결정한다. */
public record GrantRequest(UUID partnerId, Permission permission) {
    /** enum 순번이나 숫자 문자열의 자동 변환을 허용하지 않고 API에 정의된 문자열만 받는다. */
    @JsonCreator
    public static GrantRequest fromJson(
            @JsonProperty("partnerId") JsonNode partnerId,
            @JsonProperty("permission") JsonNode permission) {
        if (partnerId == null || !partnerId.isString() || permission == null || !permission.isString()) {
            throw ApiException.invalidRequest();
        }
        try {
            UUID id = UUID.fromString(partnerId.asString());
            if (!id.toString().equalsIgnoreCase(partnerId.asString())) {
                throw ApiException.invalidRequest();
            }
            return new GrantRequest(id, Permission.valueOf(permission.asString()));
        } catch (IllegalArgumentException error) {
            throw ApiException.invalidRequest();
        }
    }
}
