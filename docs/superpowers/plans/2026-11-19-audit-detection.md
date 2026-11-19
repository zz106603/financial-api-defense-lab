# 감사 이벤트 및 이상 접근 탐지 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans. 현재 브랜치에서 순차 구현하고 마지막에 독립 리뷰한다. 커밋·push·PR은 하지 않는다.

**Goal:** 새 인증·입력 오류 감사와 R-01 탐지를 추가한다.
**Architecture:** 기존 필수 감사는 유지한다. 인증/입력 오류는 별도 REQUIRES_NEW 감사 실패를 경계에서 처리해 원래401/400을 유지한다. 탐지는30초 주기로 DB 기준T의 이동창을 집계하고 DB의 주체별 억제 상태와 알림을 원자적으로 저장한다.
**Tech Stack:** Java21, Spring Security/Transactions/Scheduling, MyBatis XML, PostgreSQL/Flyway, JUnit/MockMvc/Testcontainers.
**Spec:** docs/security-requirements.md, architecture.md, data-model.md, api-spec.md 및 사용자가 승인한 아래 정책.

## Global Constraints

- High. 기존 조회/권한 변경 필수 감사 실패503과 기존 트랜잭션 경계를 바꾸지 않는다.
- 새 인증 실패/입력 오류 감사 실패는401/400을 유지하고 고정 운영 오류만 남긴다. 인증 주체 DB 장애는 기존503이다.
- 기존 금융 GET2/POST/DELETE 경로·메서드만 대상으로 한다. 인증 실패는ANONYMOUS/null, 입력 오류는 검증된 주체만 사용한다. 원문 토큰/클레임/본문/쿼리/오류/경로를 저장하지 않는다.
- DB에서 한번 얻은T로 (T-5분,T]의 커밋된 DENIED/OBJECT_ACCESS_DENIED만 집계한다. CUSTOMER/PARTNER 유형+UUID로 구분하고 가명 계좌3개 이상·횟수5회 이상일 때R-01을 생성한다.
- 마지막 성공 알림 트랜잭션의 DB시각 이후5분 억제, 이후 최신 조건 충족 시 재알림. 기준T가 이전 알림창보다 과거/동일이면 재사용하지 않는다. 억제 상태 변경과 알림 저장은 같은 트랜잭션이다.
- 자동 차단/외부 감사·관제API/메시지브로커/신규 라이브러리는 추가하지 않는다. 늦은 커밋·장기 중단 후 과거 창 소급 탐지는 범위 밖이다.
- Windows 장부는 이 계획과 ignored build 폴더에 기록한다. 현재 브랜치 사용·추가 Worktree/병렬 구현/커밋 없음. 설계 승인은 사용자 지시에 포함되어 있어 구현을 계속한다.

## Review Focus

- 인증 실패 토큰의 위조sub를 주체로 사용하지 않으며 잘못된UUID는 가명 참조를 만들지 않는다(Task1).
- 400의 Controller 이전 역직렬화 오류와 서비스의 값 오류 모두 한번만 감사한다(Task1).
- 경계T-5분 제외/T포함, 주체유형 분리 및 미존재/역할/중복/정상 이벤트 제외(Task2).
- 두 실행이 경합해도 하나만 알림을 만들고 알림 실패 시 억제 상태도 롤백(Task2).
- 탐지 장애는 API결과를 바꾸지 않으며 스케줄러는 다음 실행 가능 상태를 유지한다(Task3).

## Task1: 새 인증·입력 오류 감사

Files: audit/RequestFailureAuditService.java, common/web/RequestFailureAuditor.java(새파일), common/security/SecurityErrorHandler.java·SecurityConfiguration.java, common/web/ApiExceptionHandler.java(수정), audit/RequestFailureAuditIntegrationTest.java(새테스트).
Interfaces: RequestFailureAuditService.record(AuthenticatedPrincipal principal, UUID accountId, String action, String reason, String requestId):void(REQUIRES_NEW). RequestFailureAuditor.authenticationFailure(request,response)/invalidRequest(request,response):void(실패포착·요청내중복방지·안전한대상파싱).

- [x] 실제JWT/DB HTTP테스트로 인증누락·위조토큰·잘못된경로UUID·페이지·본문·권한값 감사, 민감정보비노출·요청ID일치,401/400유지·독립커밋·범위밖미감사를 작성한다.
- [x] `.\gradlew.bat test --tests '*RequestFailureAuditIntegrationTest' --no-daemon '-Dorg.gradle.jvmargs=-Xmx512m -Djava.net.preferIPv4Stack=true'`으로 감사 미구현 실패를 확인한다.
- [x] 대상4개API의 행동만 분류하고 새 서비스를 보안오류/입력오류 경계에 연결한다. 이미 감사한 필수경로는 다시 기록하지 않는다.
- [x] 같은 명령으로 통과를 확인한다. 감사 INSERT/지연 커밋 실패에도 원래응답·no-store·WWW-Authenticate·requestId를 유지하는지 검증한다.

## Task2: R-01 집계·억제·원자성

Files: db/migration/V2__create_detection_state.sql, detection/DetectionCandidate.java·DetectionMapper.java, mappers/detection/DetectionMapper.xml, detection/DetectionService.java, detection/DetectionIntegrationTest.java(새파일).
Interfaces: DetectionMapper.currentTime():OffsetDateTime; candidates(T):List<DetectionCandidate>; claim(ruleId,type,id,T):Optional<OffsetDateTime>(DB의 억제기준시각 반환); DetectionService.detect():int(REQUIRES_NEW/READ_COMMITTED, 생성알림수).

- [x] 실제PostgreSQL 테스트로 창경계/임계값/제외조건/유형별집계·재실행억제·5분후재알림·미충족시무알림·두연결경합·알림실패전체롤백을 먼저 작성한다.
- [x] 위파일 미구현 실패를 확인한 뒤 집계SQL과 상태PK(rule,type,id)를 구현한다. claim은 원자적UPSERT조건으로5분경과·T증가를 보장하고, 알림INSERT가 실패하면 state도롤백한다.
- [x] `.\gradlew.bat test --tests '*DetectionIntegrationTest' --no-daemon '-Dorg.gradle.jvmargs=-Xmx512m -Djava.net.preferIPv4Stack=true'` 통과를 확인한다. 테스트DB정리스크립트에 새상태테이블을 포함한다.

## Task3: 주기 실행·전체 검증·문서

Files: detection/DetectionSchedulingConfiguration.java·DetectionScheduler.java, detection/DetectionSchedulerTest.java; application-test.yaml 및 관련 정책문서.
Interfaces: DetectionScheduler.run():void는 DetectionService.detect() 호출·RuntimeException 고정로그 처리. production 기본30초 fixedRate/initialDelay, 테스트는 스케줄링만 끄고 서비스를 직접 검증한다.

- [x] 스케줄러 실패복구 테스트를 먼저 작성해 실패를 확인한 뒤 구현한다. 실제API 무단조회→감사→탐지→알림까지 SEC-11을 연결 검증한다.
- [x] 승인정책·새상태테이블·감사코드·응답·집계범위/한계·스케줄링을 관련 문서에 반영한다.
- [x] `.\gradlew.bat clean build --no-daemon '-Dorg.gradle.jvmargs=-Xmx512m -Djava.net.preferIPv4Stack=true'`, git diff --check, JAR혼입·문서일관성 검증.
- [x] 전체변경 독립리뷰1회. 지적은 타당성 확인→필요시RED/GREEN→전체회귀로 처리하고 커밋전상태로 보고한다.

## 진행 기록

- 사용자 확정 정책을 기준으로 작성. 구현·테스트 미실행. 초기 트리 clean/main에서 feat/audit-security-detection 생성.

- Task1 RED: 10개 중 감사 누락 7개 실패 확인(원래401/400 유지와 범위밖 제외 3개는 기존 동작 통과). 실제 PostgreSQL 컨테이너 준비 후 실행.

- Task1 GREEN: RequestFailureAuditIntegrationTest 10개 통과. 새 감사 서비스에서 저장/커밋 예외를 처리하고 기존 필수 감사는 변경하지 않았다.

- Task2 RED: DetectionMapper/DetectionService 부재로 컴파일 실패 확인. V2에 주체별 억제 상태 테이블을 추가하고 기존 V1·보안 테스트 기대값은 보존한다. 스키마 테스트에 새 상태 테이블 존재 확인만 확장한다.

- Task2 첫 DB 실행은 테스트 Fixture의 INSERT 자리표시자 수 오류로 9개 실패했다. 오류가 매개변수10 누락임을 확인해 실제9개 컬럼을 명시한 INSERT로 테스트 준비만 수정했다. 정책 기대값·제품 코드는 변경하지 않았다.

- Task2 GREEN: 탐지 통합10개 통과. 두 별도 DB연결이 실제 Lock 대기한 뒤 알림1개만 커밋함을 확인했다.
- Task3 RED: DetectionScheduler 부재 컴파일 실패. 구현 후 새 감사10개·집계10개·API연결/스케줄러2개 통과. 기본 스케줄러 등록과 off 직접 실행 검증도 추가해 전체 회귀에서 확인한다.
- 구현 선택: 윈도우T는 DB statement_timestamp, 억제시각은 조건부UPSERT에서 얻은 clock_timestamp를 성공 알림 detected_at과 함께 저장한다. 오래된 창은 재사용하지 않는다. 배치 전체의 상태·알림은 한 트랜잭션으로 처리한다.

- 전체 검증: 기본 인자 실행은 Gradle 데몬 소켓 바인딩 오류로 시작 전에 실패했다. JVM 시작 환경변수 JAVA_TOOL_OPTIONS=-Djava.net.preferIPv4Stack=true를 일회성 적용하고 `.\gradlew.bat clean build --no-daemon '-Dorg.gradle.jvmargs=-Xmx512m'` 실행 성공. 환경변수는 실행 후 복원했고 저장소 JVM설정은 변경하지 않았다.
- 기존157개+오류감사10개+탐지10개+API/스케줄러4개=181개, 실패·오류·건너뜀0. 배포 JAR에서 테스트 장치·Fixture·설정·키 파일·내장 개인키 없음. Mapper XML·문서 UTF-8·상대링크·git diff --check 통과.
- 독립 리뷰 진행 중. 코드 주석 정정 외 전체 빌드 후 동작 변경 없음.

- 독립 리뷰1회: Critical0/Important2/Minor1(문서 일관성). 리뷰어는 미추적 파일 포함 전체 변경·JUnit XML을 확인했으며 빌드를 독립 재실행하지 않았다.
- Important 재현: 만료 전T/만료 후claim 사례1개, 인코딩된 고정 경로/계좌UUID 사례2개를 추가해23개 중3개 실패를 확인했다. T도 last_alert_at+5분 이상인지 확인하고, MVC와 같은 PathPattern 매칭으로 수정했다.
- 과거창 재사용 테스트의 억제 만료 Fixture는 고정T와 일관되게 T-6분으로 조정했다. 동일/과거창 거부와 새창 허용 기대값은 유지한다.
- 문서의 미결정·삽입만 제공 표현과 README의5단계 표기를 현재 구현에 맞춰 정정한다. 설계/동작 변경은 없고 별도 반복 리뷰는 하지 않는다. 전체 재검증 대기.

- 최종 수정 검증: 만료 전T 후보의 선점과 인코딩 URI의3개 회귀 사례 RED→GREEN. 동일한 일회성 IPv4 JVM환경에서 clean build 성공, 전체184개 통과(기존157+오류감사12+탐지11+API/스케줄러4), 실패·오류·건너뜀0.
- 최종 JAR 비혼입 검사와 Mapper XML·문서 인코딩·상대 링크·git diff --check 통과. 코드/Mapper/마이그레이션12개·테스트/Fixture설정6개·문서8개, 총26개 변경.
- 독립 리뷰 지적 Important2건과 문서 일관성 지적을 모두 반영하고 전체 회귀를 재실행했다. 추가 독립 리뷰는 수행하지 않았다. 미해결 리뷰 지적 없음.
- 기존 필수 감사503과 새 인증/입력 오류 감사401/400 경계를 유지한다. 알림과 억제 상태는 원자적이며 자동 차단·외부 관제API는 추가하지 않았다.
- 남은 범위: 원격CI 미실행, 운영 부하·연결 풀·보존 기간 검증, 창을 벗어난 늦은 커밋/장기 중단 소급 탐지, Evals. 기본 단일 스케줄러의 처리 지연은 다음 실행 시점에 영향을 줄 수 있다.
- feat/audit-security-detection에서 미커밋으로 유지한다. 추가 Worktree·병렬 구현·커밋·push·PR을 수행하지 않았다.

## 커밋·초안 PR 준비 확인

- 이후 사용자 지시에 따라 현재 브랜치 커밋·push·초안 PR 생성을 진행한다. 위 미커밋 유지 기록은 구현 종료 당시 상태다.
- 일회성 JAVA_TOOL_OPTIONS=-Djava.net.preferIPv4Stack=true에서 `.\gradlew.bat build --no-daemon '-Dorg.gradle.jvmargs=-Xmx512m'` 성공. 제품·테스트·리소스 입력이 모두 UP-TO-DATE임을 확인해 기존184개 통과 결과를 재사용했다. 이 단계에서 테스트를 재실행한 것으로 보고하지 않는다.
- git diff --check 통과, 기존 독립 리뷰와 지적 처리 기록을 확인했다. 새 동작 변경이나 추가 독립 리뷰는 없다. 원격 CI는 PR 생성 후 로컬 검증과 구분한다.
