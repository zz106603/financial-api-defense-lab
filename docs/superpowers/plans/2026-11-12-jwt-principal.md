# JWT 인증 및 요청 주체 식별 구현 계획

**Goal:** 개발 순서 4단계의 JWT 검증과 DB 기반 요청 주체 식별을 구현한다. 커밋·push·PR은 수행하지 않는다.

**Spec:** `docs/architecture.md`의 인증 설계, `docs/security-requirements.md`의 INV-01·05, SR-01 및 JWT 추가 검증, `docs/api-spec.md`의 인증·오류 계약.

**Architecture:** Spring Security OAuth2 Resource Server가 외부 공개키로 RS256 서명을 검증한다. issuer·audience는 외부 설정이며 `exp`, `nbf`, `sub`, `principal_type`을 검증한 뒤 기존 고객/제휴사 Mapper로 DB 주체를 찾는다. 인증 결과에는 DB UUID·주체 유형과 서버가 정한 역할만 보유한다.

## 구현 선택과 범위

- 별도 인증 서버·JWKS 네트워크 의존성을 추가하지 않고 X.509 PEM RSA 공개키 파일을 사용한다. issuer·audience·공개키가 없거나 잘못되면 웹 애플리케이션 기동을 실패시킨다.
- 허용 알고리즘은 RS256만 사용한다. `exp`는 필수, `nbf`는 있으면 검증하며 시간 허용 오차는 0초다. sub는 비어 있지 않은 문자열, 유형은 CUSTOMER 또는 PARTNER만 허용한다.
- Bearer는 Authorization 헤더만 사용한다. 쿠키/로그인 세션·Basic·폼 로그인·쿼리/폼 토큰 인증은 사용하지 않는다.
- 모든 일반 요청은 인증을 요구한다. 컨테이너 ERROR 디스패치는 오류 응답 전달을 위해 허용하되 직접 `/error` 요청은 인증 대상이다.
- 인증 실패는 일반적인 JSON 401, 인증 후 역할 거부는 403, 주체 DB 장애는 민감정보 없는 503으로 처리한다. 오류 응답에서 서버 UUID 추적 ID를 생성하고 본문과 X-Request-Id에 동일하게 제공한다. 전 요청의 추적·감사 연계는 후속 범위다.
- 계좌별 권한과 scope·추가 ID 클레임은 인증 권한으로 사용하지 않는다. 객체별 인가 SQL·STATEMENT 설정은 유지한다.
- 테스트 키는 JVM 실행 중 생성하며 개인키는 파일·런타임 JAR에 포함하지 않는다. 테스트 전용 컨트롤러로 필터 체인과 주체 전달을 검증하며 제품에는 진단 API를 추가하지 않는다.
- High 위험도: 전체 기존/신규 테스트·빌드와 구현 후 순차 독립 리뷰 1회를 수행한다.

## Review Focus

- 잘못된 서명·알고리즘·issuer·audience·시각과 누락/잘못된 유형의 필수 클레임은 거부한다.
- 동일 sub가 고객·제휴사에 존재해도 유형별 테이블로만 식별한다. 없는 주체·다른 테이블의 sub는 거부한다.
- 요청 헤더/쿼리/추가 ID·scope·roles 클레임이 DB 주체나 역할을 바꿀 수 없어야 한다.
- 유효한 JWT 이후에도 새 요청은 Bearer를 요구하며 세션·쿼리 토큰으로 우회할 수 없어야 한다.
- 주체 조회 DB 장애·인증 오류에 토큰, SQL, 상세 클레임·스택이 노출되지 않아야 한다.

## Task 1: 실제 서명·필터 체인 테스트 우선

- [x] Resource Server와 Spring Security Test 의존성을 추가한다.
- [x] JVM RSA 테스트 키·서명 Fixture와 실제 PostgreSQL/MockMvc 테스트를 작성한다. JwtDecoder 모킹이나 `jwt()` 모의 인증으로 서명을 대체하지 않는다.
- [x] 미인증/잘못된 토큰 거부, 정상 고객/제휴사 DB 매핑 및 추가 클레임 무시 테스트가 구현 부재로 실패하는 것을 확인한다.

## Task 2: 인증 구현

- [x] 필수 설정·RS256 JwtDecoder와 클레임 validator를 구현한다.
- [x] DB 주체 변환기·타입이 있는 인증 주체를 구현하고 토큰 scope를 무시한다.
- [x] Stateless 필터 체인과 401/403/503 일반 오류 처리를 연결한다.
- [x] 기본 웹 기동 테스트는 인증된 실제 HTTP 요청으로 404 검증을 유지한다. 비웹 DB 테스트는 웹 보안 설정을 생성하지 않는다.
- [x] 인증 테스트를 실행하고 필요한 경계 테스트를 보완한다.

## Task 3: 문서·전체 검증·독립 리뷰

- [x] 환경변수 예제, README, 아키텍처·API·개발환경·테스트 문서를 현재 구현에 맞춘다.
- [x] `.\gradlew.bat clean build --no-daemon`, `git diff --check` 및 JAR 내 개인키/테스트 진단 API 부재를 확인한다.
- [x] 순차 독립 리뷰를 수행하고 지적 타당성을 검토해 필요한 수정·재검증을 수행한다.
- [x] 기존/신규 테스트 결과와 미구현 SEC 범위를 구분해 보고한다.

## 검증 기록

- 인증 구현 전 실제 서명·필터 체인 테스트 34개가 구현 부재로 실패했다.
- 초기 구현 후 역할 목록 비교 3개가 실패했다. 실제 응답의 역할은 정확했고, JSON 배열을 구체적인 크기·원소로 검증하도록 테스트를 수정했다. 권한 기대값은 유지했다.
- 최종 `.\gradlew.bat clean build --no-daemon`: 성공. 기존 24개 + JWT 통합 44개 + 설정 8개 = 총 76개, 실패·오류·건너뜀 0.
- `git diff --check`: 성공.
- Python zipfile로 배포 JAR의 BOOT-INF/classes를 검사해 테스트 Controller/Fixture/키 파일/내장 개인키가 없음을 확인했다.
- 이 단계에서는 금융정보 HTTP API, 계좌별 API 인가, 감사 서비스 및 전체 SEC-01~12를 완성했다고 주장하지 않는다. 원격 CI는 미실행이다.

- 순차 독립 읽기 전용 리뷰 1회: Critical 0 / Important 0 / Minor 1. 차단 결함 없음.
- Minor(후속 권고): 이미 인증된 SPRING_SECURITY_CONTEXT를 가진 세션을 명시적으로 전달했을 때 Bearer 없이 401인지 확인하는 회귀 사례 추가. 현재 STATELESS 설정은 올바르며, 세션 미생성과 새 무토큰 요청 거부는 실제 테스트했다. 이 추가 사례는 이번에 실행하지 않았다.
- 후속 범위로 분류한 금융 API 객체별 인가, 감사·탐지, JWKS 회전 및 전체 SEC-01~12는 기존 단계 계획대로 유지한다.
- 커밋·push·PR 생성은 수행하지 않았다.
