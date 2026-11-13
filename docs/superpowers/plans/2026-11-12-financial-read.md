# 금융정보 조회 API 및 객체별 인가 구현 계획

**Goal:** 개발 순서 5단계의 두 GET API와 객체별 인가를 구현한다. 커밋·push·PR은 하지 않는다.

**Spec:** `docs/api-spec.md`, `docs/security-requirements.md`, `docs/architecture.md`, `docs/data-model.md`.

**Architecture:** 인증된 DB UUID·유형을 받는 Controller → AccountQueryService → FinancialReadAuthorizationService → 기존 AccountMapper의 인가 조건 포함 SQL로 조회한다. 내부 투영과 응답 DTO를 분리하고, 같은 SQL 스냅샷에서 미존재/무권한/허용된 빈 페이지를 구분한다. 조회 허용·거부는 별도 ReadAuditService의 REQUIRES_NEW 트랜잭션으로 응답 전에 기록한다.

## 확정 범위와 구현 선택

- 사용자가 기본 size=20·최대 size=100, page>=0 및 조회용 최소 감사 포함을 확인했다.
- page는 Java int 범위이며 offset은 long으로 곱해 오버플로를 방지한다. size+1행으로 hasNext를 구한다. 정렬은 occurred_at DESC, id DESC다.
- 고객 소유권 또는 제휴사·계좌·정확한 개별 권한·미회수 상태를 실제 조회 SQL에서 평가한다. 별도 사전 권한 조회는 추가하지 않는다. MyBatis STATEMENT 설정을 유지한다.
- API용 투영은 최소 한 행의 상태를 반환한다. 거부면 금융 필드는 null이며 존재 여부는 내부 감사 사유에만 사용한다. 기존 영속성 메서드·테스트는 보존한다.
- 외부 미존재/무권한은 동일한 404 RESOURCE_NOT_FOUND, 잘못된 UUID/페이지는 400 INVALID_REQUEST, DB/감사 실패는 일반 503이다.
- 응답 DTO는 명세 필드만 제공한다. 금액은 BigDecimal JSON 숫자, 시각은 UTC ISO 8601로 반환한다.
- 계좌 가명 참조는 외부 Base64 비밀키(최소 32바이트)와 HMAC-SHA256으로 생성한다. 토큰·계좌번호·금액·거래 원문을 감사 서비스에 전달하지 않는다. 일반 코드에 기본 비밀키를 넣지 않는다. 테스트 전용 설정에만 합성 키를 둔다.
- 요청 추적 ID는 서버 UUID를 생성해 성공 헤더·오류 헤더/본문·감사에 동일하게 사용한다. 클라이언트의 추적 ID는 무시한다.
- 감사 reason_code는 ACCESS_ALLOWED / OBJECT_ACCESS_DENIED / RESOURCE_NOT_FOUND를 사용한다. 인증 실패·입력 오류는 R-01 권한 위반으로 분류하지 않는다. 이 단계에서 탐지를 실행하지 않는다.
- 권한 변경 API·감사, 탐지 집계·중복 억제와 실습용 발급 도구는 후속 단계다. 기존 보안 정책을 완화하지 않는다.
- High 위험도이며 전체 회귀 테스트·빌드와 구현 후 순차 독립 리뷰 1회를 수행한다. 병렬 구현·추가 Worktree를 사용하지 않는다.

## Review Focus

- 거래가 없는 계좌와 페이지 끝은 허용될 때만 빈 200이며 권한 없음은 404다.
- ACCOUNT_READ/TRANSACTION_READ가 서로의 권한을 포함하거나 다른 계좌/제휴사로 확장되지 않는다.
- 회수 커밋 이후 새 SQL은 같은 토큰·세션에서도 거부한다.
- 응답에 내부 소유자·부여자·auth_subject·인가 상태를 노출하지 않는다.
- 감사 저장 실패로 금융정보를 반환하지 않으며 거부 감사는 업무 롤백 이후에도 남는다.
- HMAC 키/원문 금융정보·토큰·SQL/예외 설명을 응답이나 감사에 넣지 않는다.

## Task 1: 인가·페이지 SQL 투영

- [x] FinancialReadProjectionIntegrationTest를 먼저 작성하고 Mapper 메서드 부재 실패를 확인한다.
- [x] AccountReadRow, TransactionPageRow와 AccountMapper readAccount/readTransactionPage를 구현한다.
- [x] 실제 PostgreSQL에서 존재·인가·허용된 빈 결과·금융 필드 비노출과 정렬/페이지를 검증한다.

## Task 2: API·감사·오류 경계

- [x] AccountQueryApiIntegrationTest에 실제 JWT 기반 허용·거부·응답 필드·입력·회수·감사 테스트를 먼저 작성하고 실패를 확인한다.
- [x] AccountController, AccountQueryService, FinancialReadAuthorizationService 및 응답 record를 구현한다.
- [x] ReadAuditService와 ResourceReferenceGenerator를 구현하고 키·안정적 가명화·별도 감사 커밋을 검증한다.
- [x] RequestIds, ApiException/ApiExceptionHandler를 연결하고 기존 보안 오류도 같은 요청 ID를 사용한다.
- [x] 기본값/상한/큰 page와 DTO 정밀도, 감사 실패·금융 DB 실패 시 비노출 및 업무 롤백 후 거부 감사 유지를 검증한다.

## Task 3: 문서·전체 검증·독립 리뷰

- [x] API·아키텍처·데이터 모델·개발환경·테스트 문서와 환경변수 예제를 갱신한다.
- [x] `.\gradlew.bat clean build --no-daemon`, `git diff --check`, 배포 JAR의 테스트/비밀키 혼입 검사를 실행한다.
- [x] 순차 독립 리뷰의 지적을 검토하고 필요한 수정과 관련 재검증을 수행한다.
- [x] 실제 검증과 SEC-08·11 등 후속 범위를 구분해 미커밋 상태로 보고한다.

## 검증 기록

- API용 SQL 투영 테스트는 메서드 부재로 실패한 뒤 실제 PostgreSQL에서 3개 통과했다.
- HTTP 계약 테스트는 구현 전 32개 중 30개 실패했고, 기존 인증 경계 2개는 통과했다. 구현 후 페이지/감사 경계 보완까지 API 34개가 통과했다.
- `.\gradlew.bat clean build --no-daemon`: 전체 118개 통과, 실패·오류·건너뜀 0, 실행 JAR 생성.
- `git diff --check`: 성공.
- 최소 감사 포함과 페이지 기본 20·최대 100은 사용자 답변을 반영했다. 탐지·권한 변경 API/감사는 범위 밖으로 유지한다.

- Python zipfile 검사: 배포 JAR의 애플리케이션 영역에 테스트 설정/Controller/Fixture/키 파일/내장 개인키/합성 HMAC 키 없음.
- 순차 독립 읽기 전용 리뷰 1회: Critical 0 / Important 0 / Minor 0. 완료를 막는 지적 없음. 리뷰어는 제품·문서·테스트를 읽었고 테스트를 독립 재실행하지는 않았다.
- 남은 범위: 권한 변경 API·감사 원자성, 인증/입력 오류 감사, 탐지(SEC-11), 회수 API 성공 응답과 진행 중 조회 경합, 실습용 발급 도구. SEC-08의 API 검증 및 전체 SEC-01~12 완료로 보고하지 않는다.
- 원격 GitHub Actions는 미실행이다. 외부 감사 HMAC 키 설정이 추가로 필요하며 실제 .env는 수정하지 않았다.
- 작업 브랜치 feat/financial-read-authorization에서 미커밋 상태로 유지했다. 커밋·push·PR은 수행하지 않았다.
