package lab.financialapi.common.web;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** 성공·오류·감사가 같은 서버 생성 ID를 사용한다. 요청 헤더의 ID는 신뢰하지 않는다. */
public final class RequestIds {
    private static final String ATTRIBUTE = RequestIds.class.getName() + ".id";

    private RequestIds() { }

    /** Controller/오류 처리기가 같은 요청에서 호출하면 같은 ID를 반환하고 응답 헤더에도 설정한다. */
    public static String getOrCreate(HttpServletRequest request, HttpServletResponse response) {
        // 요청 내부 attribute만 재사용한다. 클라이언트의 X-Request-Id 헤더를 읽는 것이 아니다.
        String id = (String) request.getAttribute(ATTRIBUTE);
        if (id == null) {
            id = UUID.randomUUID().toString();
            request.setAttribute(ATTRIBUTE, id);
        }
        response.setHeader("X-Request-Id", id);
        return id;
    }
}
