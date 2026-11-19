# 제휴사 권한 부여·회수 및 동시성 검증 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. 구현은 현재 세션에서 순차 수행하고 High 변경의 독립 리뷰는 별도로 수행한다.

**Goal:** 개발 순서 6단계의 POST/DELETE 권한 변경 API와 감사 원자성·실제 PostgreSQL 동시성을 검증한다. 아래 체크리스트와 검증 기록으로 구현 진행 상태를 관리한다.

**Architecture:** 기존 JWT 주체, AccessGrantMapper, 부분 UNIQUE 인덱스와 조건부 UPDATE를 재사용한다. Controller → 조정 서비스 → 별도 트랜잭션 서비스에서 변경과 성공 감사를 커밋하며, 거부 감사는 실패한 업무 트랜잭션 종료 후 별도 Bean의 독립 트랜잭션으로 저장한다.

**Tech Stack:** Java 21, Spring Boot, Spring Security, MyBatis XML, PostgreSQL, JUnit/MockMvc/Testcontainers.

**Spec:** `docs/api-spec.md` §5~7, `docs/architecture.md` §6, `docs/data-model.md` §3.5·5, `docs/security-requirements.md` INV-01~07·SR-04·SEC-07/08/12.

## Global Constraints

- High 위험도. 기존 보안 정책·테스트 기대값을 완화하지 않는다.
- 반복 DELETE는 인증·소유권·계좌/grant 관계 확인 후 204. 미존재/무권한은 동일 404.
- 권한 변경 필수 감사 실패는 503 SERVICE_UNAVAILABLE이며 변경 전체 롤백. 반복 회수도 필수 감사 실패 시 503.
- 기존 부분 UNIQUE + 조건부 UPDATE 사용. 별도 명시적 lock·권한 캐시·신규 의존성·스키마 변경은 추가하지 않는다.
- 회수 커밋 이후 시작한 조회 SQL은 거부. 이전에 시작한 조회는 완료될 수 있다. 새 grantId 재부여는 허용한다.
- JWT 검증 결과만 요청 주체로 사용하고, 응답 DTO와 HMAC 가명 감사·서버 생성 requestId를 유지한다.
- main에서 수정하지 않으며 기존 변경을 보존한다. 명시적 요청 없이 commit·push·PR·추가 Worktree·병렬 구현을 하지 않는다.
- 인증/입력 오류 전체 감사, 탐지 SEC-11, Evals는 후속 단계다.

## Review Focus

- 타인 계좌와 계좌/grant 불일치를 반복 회수 204로 오인하지 않는다(Task 2).
- 동시 UPDATE 0행은 후속 소유자 조회로 확인하고 회수 시각·최초 회수 감사를 중복 변경하지 않는다(Task 2·3).
- 활성 권한 인덱스 이외의 무결성 오류를 409로 숨기지 않고 실패한 트랜잭션에서 감사 SQL을 계속하지 않는다(Task 1·3).
- 감사 실패·커밋 실패에서 성공 응답을 반환하지 않으며 거부 감사는 호출 측 롤백에도 유지한다(Task 1·2·3).
- 이전 grantId 반복 회수가 새 grantId 권한까지 취소하지 않는다(Task 2·3).

## 파일 및 인터페이스

새 파일 기본 경로는 `src/main/java/lab/financialapi/`다.

- `authorization/AccessGrantController.java`: POST/DELETE, CUSTOMER 역할, 표준 UUID·JSON 입력 검증, requestId 연결.
- `authorization/GrantRequest.java`: `UUID partnerId, Permission permission` 요청 DTO. null·미지원 권한은 400.
- `authorization/GrantResponse.java`: 명세의 grantId/accountId/partnerId/permission/grantedAt만 직렬화.
- `authorization/AccessGrantService.java`: 외부 조정 및 트랜잭션 종료 후 거부 감사. `grant(AuthenticatedPrincipal principal, UUID accountId, GrantRequest request, String requestId): GrantResponse`, `revoke(AuthenticatedPrincipal principal, UUID accountId, UUID grantId, String requestId): void`.
- `authorization/AccessGrantTransactionService.java`: 같은 인자의 grant/revoke 메서드로 소유권·관계 확인, 변경 및 성공 감사 수행. 별도 Bean 프록시의 쓰기 트랜잭션으로 커밋 완료 후 반환. 오래된 호출 측 스냅샷을 상속하지 않도록 REQUIRES_NEW·READ_COMMITTED 적용.
- `audit/GrantAuditService.java`: `record(principal, accountId, action, allowed, reasonCode, requestId): void`(principal은 AuthenticatedPrincipal, accountId는 UUID, action/reasonCode/requestId는 String). MANDATORY 전파로 변경 트랜잭션 참여. action은 GRANT_CREATE/GRANT_REVOKE, 성공 사유는 GRANT_CREATED/GRANT_REVOKED/GRANT_ALREADY_REVOKED.
- `audit/GrantDeniedAuditService.java`: 같은 record 인터페이스를 REQUIRES_NEW로 제공. 사유는 OBJECT_ACCESS_DENIED/RESOURCE_NOT_FOUND/ACTIVE_GRANT_CONFLICT/PRINCIPAL_TYPE_DENIED. 두 감사 서비스는 토큰·금융 DTO를 받지 않고 기존 ResourceReferenceGenerator/AuditEventMapper를 재사용한다.
- 기존 `authorization/AccessGrantMapper.java`·XML, `account/AccountMapper.java`·XML, `identity/PartnerMapper.java`·XML: 소유 계좌/제휴사 존재 확인에 필요한 최소 메서드만 확장한다. 변경 SQL의 기존 소유권 조건을 보존한다.
- 기존 `common/web/ApiException.java`·`ApiExceptionHandler.java`: 409와 JSON 본문 오류 400, authorization 패키지 적용 범위 및 고정 503 처리 확장. 기존 조회 오류 회귀 방지.
- 테스트: `src/test/java/lab/financialapi/authorization/AccessGrantApiIntegrationTest.java`, `AccessGrantConcurrencyIntegrationTest.java`; 기존 persistence/ConcurrencyIntegrationTest와 account/AccountQueryApiIntegrationTest는 보존한다.

## Task 1: 권한 부여와 감사 원자성

- [x] 실제 서명 JWT·PostgreSQL 기반 AccessGrantApiIntegrationTest를 먼저 작성한다. 본인 계좌 부여 201·응답 필드 제한·두 권한 독립·재부여 새 ID·미인증 401·PARTNER 403·타인/미존재 계좌/미존재 제휴사 404·잘못된 UUID/JSON/permission/null 400·중복 409를 단언한다.
- [x] `.\gradlew.bat test --tests '*AccessGrantApiIntegrationTest' --no-daemon`으로 API 미구현에 따른 실패를 확인한다.
- [x] 인증된 PARTNER의 권한 변경 역할 거부는 입력 오류/인증 실패와 구별해 독립 감사 후 403으로 응답한다. Controller의 역할 제한에서 조정 서비스가 호출되지 않는 경우에도 누락되지 않게 권한 변경 경로 전용 거부 처리 경계를 연결한다. 검증된 주체·파싱 가능한 대상의 가명 참조·동일 requestId·PRINCIPAL_TYPE_DENIED를 확인하고, 해당 감사 실패는 503임을 POST/DELETE 모두 테스트한다. 전체 인증/입력 오류 감사로 범위를 확대하지 않는다.
- [x] 위 인터페이스의 POST 경로, 조정/트랜잭션/감사 서비스와 최소 Mapper 확장을 구현한다. 정확한 활성 권한 인덱스 충돌만 트랜잭션 밖에서 409로 바꾼다. DB/커밋/감사 실패는 고정 503이다.
- [x] 감사 INSERT 실패를 DB 수준에서 주입해 권한 0건·성공 감사 0건·503을 단언한다. 충돌 거부 감사의 독립 커밋, 민감정보 비노출·동일 requestId 및 다른 무결성 오류가 409가 아닌 것도 검증한다. 테스트 DB의 주입 장치는 반드시 정리한다.
- [x] 같은 명령으로 Task 1 테스트 통과를 확인한다.

## Task 2: 조건부 회수와 반복 DELETE

- [x] 최초 회수 204·revoked_at 설정·GRANT_REVOKED 감사 1건, 반복 회수 204·시각 보존·GRANT_ALREADY_REVOKED 감사, 타인/계좌 불일치/미존재 404·PARTNER 403 테스트를 먼저 추가하고 실패를 확인한다.
- [x] DELETE 경로와 revoke 인터페이스를 구현한다. 조건부 UPDATE 1행이면 실제 회수, 0행이면 소유자 조건의 새 조회로 이미 회수된 상태를 확인한다. 실패한 업무 트랜잭션을 끝낸 후 거부 감사를 저장한다.
- [x] 최초·반복 회수 감사 실패 모두 503인지 확인한다. 최초 실패는 권한 활성 상태를 유지하며 반복 실패는 기존 회수 상태·시각을 유지한다. 거부 감사가 호출 측 롤백 후에도 남고 응답/로그에 SQL·토큰이 없는지 단언한다.
- [x] 회수 → 새 ID 재부여 → 이전 ID DELETE 순서에서 새 권한 유지와 두 조회 권한의 독립 회수를 검증한다.
- [x] `.\gradlew.bat test --tests '*AccessGrantApiIntegrationTest' --no-daemon` 통과를 확인한다.

## Task 3: 실제 커밋 순서·경합 검증

- [x] AccessGrantConcurrencyIntegrationTest를 작성한다. latch/barrier와 제한 시간으로 순서를 제어하고 임의 sleep·테스트 병렬 설정에 의존하지 않는다. 각 작업은 별도 연결/트랜잭션을 사용한다.
- [x] 동시 POST는 201 하나·409 하나·활성 권한 1건·성공 감사 1건인지 확인한다. 첫 INSERT가 감사 실패로 롤백되면 대기하던 경쟁 요청이 정상 생성 가능한지도 확인한다.
- [x] 동시 DELETE는 둘 다 204·실제 회수 감사 1건·반복 감사 1건·동일 revoked_at인지 확인한다. 첫 회수가 감사 실패로 롤백되면 다른 회수가 실제 회수를 완료하는지도 검증한다.
- [x] 회수 미커밋 동안 기존 권한 조회 가능, 회수 성공 응답 이후 같은 JWT의 새 계좌/거래 조회 거부를 확인한다. 이전 스냅샷으로 시작한 진행 중 조회는 허용 가능하되 새 SQL은 거부되는 경합을 기존 투영/동일 세션 테스트와 함께 검증한다.
- [x] 재부여와 이전 grantId 회수 경합에서 활성 권한 최대 1건·기존 grantId만 회수·성공 감사와 실제 상태 일치를 확인한다. 경쟁 중인 POST는 충돌 시점에 따라 409 또는 회수 후 201이 가능함을 구분한다.
- [x] `.\gradlew.bat test --tests '*AccessGrantApiIntegrationTest' --tests '*AccessGrantConcurrencyIntegrationTest' --tests '*ConcurrencyIntegrationTest' --no-daemon` 통과를 확인한다.

## Task 4: 문서·전체 회귀·독립 리뷰

- [x] API/architecture/data-model의 6단계 미구현 표기를 실제 구현 상태에 맞춰 갱신하고 위 감사 action/reason_code·오류 계약·트랜잭션 경계를 문서화한다. testing-ci에 구현된 클래스와 실행 명령을 추가한다.
- [x] `.\gradlew.bat clean build --no-daemon`과 `git diff --check`를 실행한다. Docker·Java 21 등 환경 문제로 못 돌린 검증은 미검증으로 기록한다. 현재 존재하는 전체 보안 회귀 테스트와 SEC-07/08/12의 실제 커버리지를 확인하며 미구현 SEC-11을 통과로 보고하지 않는다.
- [x] 구현을 마친 뒤 순차 독립 리뷰 1회로 SQL·트랜잭션 경계·감사·경합 테스트를 확인한다. 타당한 지적을 수정한 경우 관련 테스트를 재실행한다.
- [x] 실제 실행 명령·결과·남은 범위·리뷰 결과를 기록하고 미커밋 상태로 보고한다. 원격 CI는 실제 실행 여부를 구분한다.

## 현재 검증 상태

- Task 1/2: 최초 API 테스트 19개 중 미구현 18개 실패·미인증 1개 통과, 회수 추가 후 26개 중 25개 실패를 확인한 뒤 구현해 26개 통과했다.
- Task 3: API 26개·신규 경합 7개·기존 경합 3개 통과. 실제 별도 연결 대기와 감사 실패 후 경쟁 요청 진행을 확인했다.
- 추가 검증에서 permission 숫자 0이 enum 순번으로 허용되는 결함을 발견했다(30개 중 1개 실패). GrantRequest의 문자열 검증으로 수정한 뒤 API 30개 통과했다. 감사 INSERT 이후 지연 커밋 실패도 롤백·503을 확인했다.
- 구현 선택: 부분 UNIQUE에 대응하는 ON CONFLICT (...) WHERE revoked_at IS NULL DO NOTHING을 사용해 해당 충돌만 0행으로 받는다. 업무 거부로 롤백 후 독립 감사한다. SQL 예외 메시지 파싱을 피하며 기존 insert는 보존한다.
- 구현 선택: CUSTOMER 검사를 조정 서비스에서 거부 감사와 함께 수행해 Controller 선행 역할 차단으로 감사를 누락하지 않는다. 전체 인증/입력 오류 감사 범위를 넓히지 않는다.
- 현재 브랜치를 재사용하고 추가 Worktree·병렬 구현·커밋·push·PR을 수행하지 않는다. Windows에서는 skill의 shell 장부 스크립트 대신 작업 장부를 기록한다.
- `.\gradlew.bat clean build --no-daemon`: 전체 155개 통과, 실패·오류·건너뜀 0. 배포 JAR에서 테스트 전용 경합 장치/JWT Fixture/설정/키 파일/내장 개인키 없음. `git diff --check` 통과. 최종 독립 리뷰 진행 중.

- 최종 독립 읽기 전용 리뷰: Critical 0 / Important 0 / Minor 2. 리뷰어는 전체 변경과 JUnit XML을 확인했으며 전체 테스트를 독립 재실행하지 않았다.
- 리뷰 지적 처리: Base64/Base64URL partnerId 수용은 표준 UUID 계획 계약의 필수 보완으로 판단했다. HTTP 테스트 2개를 추가해 400 기대/201 응답으로 실패를 재현한 뒤, 요청 원문에서 표준 UUID를 검증하도록 수정했다. 테스트 문서의 2~5단계/118개 표기도 현재 범위로 갱신했다. 최종 전체 회귀 결과는 다음 기록에서 확정한다.

- 최종 재검증: `.\gradlew.bat clean build --no-daemon` 성공. API 32개·경합 7개를 포함한 전체 157개 통과, 실패·오류·건너뜀 0. Base64/Base64URL HTTP 2개는 RED→GREEN을 확인했다. 배포 JAR의 테스트 장치·JWT Fixture·키 파일·내장 개인키 비혼입 검사 통과.
- `git diff --check`, 수정 Mapper XML 파싱, 문서 UTF-8 및 상대 링크 검사 통과. 코드/Mapper 14개·테스트 4개·문서 6개, 총 24개 파일 변경.
- 독립 리뷰 지적 2건 모두 반영 후 전체 회귀로 검증했다. 추가 리뷰는 실행하지 않았으며 미해결 지적은 없다.
- 남은 범위: 인증/입력 오류 전체 감사, 탐지 SEC-11, Evals, 원격 GitHub Actions 실제 실행. 운영 동시 부하·연결 풀 적정성은 별도 확인이 필요하다. 전체 SEC-01~12 완료로 보고하지 않는다.
- 브랜치 `docs/partner-grant-plan`과 기존 작업 폴더를 유지한다. 추가 Worktree·병렬 구현·커밋·push·PR을 수행하지 않았다.

## 2026-11-19 PR 준비 재검증

- 사용자 요청에 따라 현재 브랜치의 커밋·push·초안 PR 생성을 진행한다. 위 미커밋 유지 기록은 11월 13일 구현 종료 당시 상태다.
- 기본 clean build는 Gradle 데몬 소켓 바인딩 오류로 시작하지 못했다. 일회성 IPv4 옵션으로 시작한 첫 실행은 Docker Desktop 중지로 통합 테스트 초기화에 실패했다. 코드·테스트·저장소 설정은 변경하지 않았다.
- Docker Desktop을 시작한 뒤 `.\gradlew.bat clean build --no-daemon '-Dorg.gradle.jvmargs=-Xmx512m -Djava.net.preferIPv4Stack=true'` 재실행 성공: 전체 157개 통과, 실패·오류·건너뜀 0. 배포 JAR 비혼입 검사 통과.
- 기존 독립 리뷰와 지적 처리 기록을 재확인했다. 이번 점검에서는 새 독립 리뷰를 수행하지 않았다. 원격 CI 결과는 로컬 검증과 별도로 확인한다.
