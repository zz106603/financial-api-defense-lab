# 기본 테스트 및 CI

## 1. 범위

[개발 순서](project-overview.md#9-개발-진행-순서)의 2~7단계로, 애플리케이션 기동과 PostgreSQL·MyBatis, JWT 인증·주체 매핑, 금융 조회·권한 변경 API·객체별 인가·감사 원자성·경합을 검증하고 같은 검증을 GitHub Actions에서 실행한다.

| 대상 | 현재 검증 |
|---|---|
| PostgreSQL·MyBatis·Flyway | 앱의 SqlSessionFactory로 격리된 PostgreSQL에 연결하고 Flyway 이력 테이블·검증 결과 확인 |
| 웹 기동 | 임의 포트로 실제 서버를 실행하고 유효한 서명 토큰으로 미매핑 경로의 HTTP 404 확인 |
| 업무 스키마 | 7개 업무 테이블·탐지 상태 테이블, 필수 값·FK·소유자 복합 FK·권한 CHECK·활성 권한 중복·회수 이력·감사 주체·탐지 창 제약 |
| XML Mapper | sub별 주체 매핑, UUID·금액·시각·enum 매핑, 고객 소유권·제휴사별 계좌/권한 범위·회수 상태, 거래 정렬/페이지 격리, 감사·탐지 저장 |
| DB 경합·가시성 | 독립 트랜잭션의 동시 활성 권한 부여 중 1건만 커밋, 회수 커밋 후 동일 MyBatis 세션의 새 조회 거부 |
| JWT·요청 주체 | 실제 RSA 서명·변조·알고리즘·클레임 검증, PostgreSQL 주체 매핑·추가 ID/scope 무시·재식별, Bearer 전용/Stateless, 401/403/503 비노출 오류 |
| 인증 설정 | 필수 값 누락, 잘못된 PEM·없는 파일·2048비트 미만 RSA 키 거부 |
| 조회 API | 실제 JWT·DB로 본인/위임 조회 허용, 객체·행위 범위 초과 404, 빈 페이지 200, 엄격한 UUID·페이지 검증, DTO 필드·정밀 금액·UTC 시각·no-store |
| 조회 감사 | 허용/거부·미존재 사유 구분, HMAC 가명화, 추적 ID 일치·민감정보 비저장, 업무 롤백 후 거부 기록 유지, 감사/금융 DB 장애 시 503·정보 비노출 |
| 권한 변경 API·감사 | 본인 계좌 부여/회수·반복 204, 역할/객체 거부 감사, 활성 충돌 409, 필수 감사 INSERT/커밋 실패 롤백·503, 표준 UUID/문자열 권한 입력 제한 |
| 권한 변경 경합 | 실제 연결 대기, 동시 부여/회수, 선행 감사 롤백 후 경쟁 요청 진행, 회수 전후 조회 및 재부여 이력 분리 |
| 빌드 | Java 21 컴파일, 테스트, 실행 JAR 패키징 |

형식적인 모델 getter 단위 테스트는 추가하지 않는다. 현재 `ApplicationIntegrationTest` 2개, `SchemaIntegrationTest` 10개, `PersistenceIntegrationTest` 10개, `ConcurrencyIntegrationTest` 3개, `JwtAuthenticationIntegrationTest` 44개, `JwtConfigurationTest` 8개, `FinancialReadProjectionIntegrationTest` 3개, `AccountQueryApiIntegrationTest` 34개, `ResourceReferenceGeneratorTest` 4개, `AccessGrantApiIntegrationTest` 32개, `AccessGrantConcurrencyIntegrationTest` 7개와7단계 오류 감사·탐지 테스트를 구성한다. 최종 개수는 아래 검증 기록을 따른다. 매개변수화 테스트는 각 입력 사례를 한 개로 계산한다.

조회 API에서 SEC-01~07·09·10과 조회 범위의 SEC-12를 검증한다. 6단계에서 SEC-08의 권한 변경 API와 감사 원자성·회수 API 경합을 추가 검증한다. 7단계는 SEC-11의 실제API 감사→알림 연결과 네 금융API의 인증/입력 오류 감사를 검증한다. Evals·운영 부하·외부 관제 검증과는 구분한다.

금융 조회 테스트는 실제 서명 JWT → 보안 필터 → DB 주체 식별 → Controller/Service → 인가 SQL → 독립 감사 커밋을 연결한다. ACCOUNT_READ와 TRANSACTION_READ의 독립성, 허용된 빈 계좌·페이지와 거부의 구분, 동일 외부 404와 별개 내부 감사 사유, BigDecimal 정밀도와 내부 필드 비노출을 확인한다. 감사/금융 DB 장애는 테스트 DB 테이블 이름을 일시 변경하고 finally에서 복원한다. 업무 트랜잭션을 실제 롤백한 후 별도 감사 커밋이 남는지도 확인한다. SQL 검증은 Mockito/H2로 대체하지 않는다.

JWT 인증 테스트는 실제 RSA 개인키로 서명한 토큰을 Authorization 헤더로 보내 Resource Server 필터와 실제 Decoder, 고객/제휴사 Mapper를 통과한다. 서명 변조·다른 키·RS512·HS256/공개키 혼동·서명 없는 토큰, 잘못된 issuer/audience·만료/미활성 시각·누락/잘못된 클레임·미등록 주체를 거부한다. 같은 sub의 유형 분리, 추가 scope/roles/ID 무시, 매 요청 주체 재조회, Basic/쿼리/폼 토큰 거부, 세션 미생성, 역할 거부와 주체 DB 장애도 검증한다. DB 장애는 테스트 트랜잭션 내 테이블 이름 변경으로 재현하고 롤백한다.

테스트에는 JwtDecoder 모킹이나 `jwt()` 모의 인증을 사용하지 않는다. 향후 모의 인증 기반 API 인가 테스트를 추가하더라도 실제 서명 검증 테스트와 구분하고 이 테스트를 대체하지 않는다. 진단용 Controller와 RSA 개인키는 테스트 클래스에만 있고 개인키 파일을 만들지 않는다. 기본 웹 기동 테스트도 실제 서명 토큰과 테스트 DB Fixture를 사용하며, 미인증 요청을 허용하도록 필터 체인을 우회하지 않는다.

## 2. 로컬 실행

Java 21과 실행 중인 Docker Linux 엔진이 필요하다. Testcontainers는 `postgres:18.1` 컨테이너와 임의의 호스트 포트를 사용하며 테스트 종료 시 컨테이너를 정리한다. Docker가 없으면 테스트를 건너뛰지 않고 실패한다.

```powershell
.\gradlew.bat test --no-daemon
.\gradlew.bat clean build --no-daemon
```

특정 클래스만 확인하거나 캐시된 결과 대신 다시 실행하려면 다음 명령을 사용한다.

```powershell
.\gradlew.bat test --tests lab.financialapi.ApplicationIntegrationTest --rerun-tasks --no-daemon
```

macOS/Linux에서는 `.\gradlew.bat` 대신 `./gradlew`를 사용한다. `gradlew`는 Git 실행 권한 `100755`와 LF 줄바꿈을 유지해야 한다.

테스트는 `test` 프로필과 동적으로 전달한 컨테이너 연결 정보를 사용한다. 로컬 `.env`나 Compose의 기존 DB·데이터 볼륨을 사용하지 않는다. Flyway와 MyBatis의 자동 구성을 비활성화하거나 H2로 대체하지 않는다.

웹 인증 테스트는 테스트 JVM이 생성한 RSA 공개키 임시 파일과 테스트 issuer/audience를 동적으로 등록한다. 운영용 JWT 설정이나 외부 인증 서버 없이 실행된다. 인증 테스트는 MockMvc에 실제 Spring Security 필터를 적용하고, 기본 웹 기동 테스트는 실제 HTTP 서버를 사용한다.

조회 감사의 HMAC 키는 `src/test/resources/application-test.yaml`에만 고정 합성 키를 제공한다. 외부 비밀값이 필요하지 않고 일반 애플리케이션의 기본 키나 배포 JAR에 포함하지 않는다. 가명화 단위 테스트는 같은 키/계좌의 안정성, 서로 다른 키/계좌의 분리와 설정 누락·잘못된 Base64·짧은 키 거부를 확인한다.

PostgresIntegrationSupport를 상속하는 테스트는 하나의 JVM 수명 PostgreSQL 컨테이너를 공유하고 Ryuk이 정리한다. 스키마·Mapper·JWT 테스트는 테스트 트랜잭션으로 롤백한다. 경합·조회 API 테스트는 실제 커밋이 필요하므로 테스트 전후 테스트 DB만 비우고 고정 Fixture를 다시 삽입한다. 병렬 테스트 실행은 설정하지 않는다. `@Sql`의 정리 스크립트는 일반 애플리케이션 DB에 실행하면 안 된다.

```powershell
.\gradlew.bat test --tests '*SchemaIntegrationTest' --no-daemon
.\gradlew.bat test --tests '*PersistenceIntegrationTest' --tests '*ConcurrencyIntegrationTest' --no-daemon
.\gradlew.bat test --tests '*JwtAuthenticationIntegrationTest' --tests '*JwtConfigurationTest' --no-daemon
.\gradlew.bat test --tests '*AccountQueryApiIntegrationTest' --tests '*FinancialReadProjectionIntegrationTest' --tests '*ResourceReferenceGeneratorTest' --no-daemon
```

회수 가시성 테스트는 `READ COMMITTED` 및 같은 SqlSession의 반복 조회를 사용한다. 기존 계좌 조회와 새 API용 계좌/거래 투영 모두 별도 회수 커밋 이후 다시 조회하면 거부되는지 확인한다. `SESSION` 캐시로 변경하면 이전 허용 결과가 재사용되어 실패하므로 기본 캐시로 퇴행하는 경우도 탐지한다. HTTP에서도 같은 JWT로 회수 커밋 전후 계좌 조회를 확인한다. 6단계의 AccessGrantConcurrencyIntegrationTest는 회수 HTTP API 성공 응답 순서·진행 중 조회 경합도 검증한다.

실행 결과는 `build/reports/tests/test/index.html`, JUnit XML은 `build/test-results/test/`에서 확인한다. 테스트 의존성은 Spring Boot BOM으로 버전을 관리하며 애플리케이션 런타임에 포함하지 않는다.

## 3. GitHub Actions

워크플로는 `.github/workflows/ci.yaml`의 `CI`, 작업은 `Build and test`다.

| 항목 | 동작 |
|---|---|
| 트리거 | 모든 PR, `main` push, 수동 실행 |
| 실행 환경 | GitHub-hosted Ubuntu 24.04, Temurin Java 21, 저장소 Gradle Wrapper |
| 사전 확인 | Docker 사용 가능 여부, `gradlew` 실행 권한 |
| 실행 명령 | `./gradlew clean build --no-daemon` |
| 실패 처리 | 컴파일·테스트·패키징 실패를 작업 실패로 반영 |
| 결과 보존 | 취소되지 않은 실행의 테스트 HTML·XML을 `test-reports` 아티팩트로 7일간 보존; 보고서 생성 전 실패하면 파일 없음 경고 |
| 동시 실행 | 같은 PR 또는 브랜치의 이전 실행을 취소 |

외부 Actions는 공식 저장소의 커밋 SHA로 고정한다. 토큰은 `contents: read`만 부여하고 checkout 자격정보를 저장하지 않는다. Gradle 캐시는 `main` 실행에서만 쓰고 PR에서는 읽기만 허용한다. 테스트에 저장소 비밀값이나 로컬 DB 자격정보는 필요하지 않다.

현재 CI는 모든 테스트를 하나의 `test` 작업으로 실행하며 경로별 생략·실패 무시·Docker 부재 시 건너뛰기를 적용하지 않는다. 이후 보안 테스트도 기본 빌드에 포함한다. 위험도 분류·독립 리뷰 등 하네스 강제의 상세 구현은 [아키텍처의 후속 구현 결정](architecture.md#10-후속-구현-결정)에 따라 남겨 둔다. 저장소의 브랜치 보호·필수 상태 검사 설정은 이 파일만으로 변경되지 않는다.

## 4. 검증의 구분

로컬 Gradle 실행과 workflow 정적 검사는 GitHub Actions 실제 실행을 대신하지 않는다. 커밋·push·PR 생성 이전에는 원격 실행 결과를 확인할 수 없으므로 미검증으로 구분한다.

CI 및 보안 테스트 변경은 AGENTS.md의 High 위험도 기준을 따르며 관련 테스트, 존재하는 전체 보안 회귀 테스트와 독립 리뷰를 수행한다. 미구현된 보안 회귀 검증을 통과한 것으로 보고하지 않는다.

## 5. 6단계 권한 변경 검증

구현 계획은 [제휴사 권한 부여·회수](superpowers/plans/2026-11-13-partner-grants.md)를 따른다. AccessGrantApiIntegrationTest는 실제 서명 JWT의 API 계약·인가·감사 원자성을 검증하고, AccessGrantConcurrencyIntegrationTest는 별도 연결의 실제 대기를 pg_blocking_pids로 확인한다. 테스트 전용 interceptor와 DB 트리거는 반환 경계 동기화·실패 주입에만 사용하고 제품 잠금이나 Mapper 모킹을 추가하지 않는다.

- 실제 서명 JWT로 CUSTOMER만 본인 계좌 권한을 부여·회수하며 PARTNER·타인 계좌 변경은 거부한다. 인증된 PARTNER 역할 거부도 독립 감사하며 해당 감사 실패는 503이다.
- 반복 DELETE는 소유권·계좌/grant 관계 확인 후 204, 미존재/무권한은 404다. 기존 revoked_at을 유지한다.
- 최초·반복 회수 모두 필수 감사 실패는 503이며 변경·성공 감사는 같은 트랜잭션으로 롤백한다. 거부 감사는 별도 트랜잭션으로 유지한다.
- 동시 부여는 부분 UNIQUE로 활성 1건을 보장하고 해당 인덱스 충돌만 409다. 동시 회수는 조건부 UPDATE와 후속 조회로 두 요청 모두 204·실제 회수 감사 1건을 검증한다.
- 별도 명시적 lock 없이 실제 PostgreSQL·별도 연결·동기화 신호로 커밋 전후를 제어한다. 감사 실패 후 경쟁 요청의 진행과 재부여/이전 grantId 회수도 검증한다.
- 회수 성공 응답 이후 같은 JWT의 새 계좌·거래 조회는 거부한다. 커밋 전에 시작한 조회의 완료 가능성은 유지한다.
- 구현 후 관련 테스트·전체 기존 보안 회귀/빌드·독립 리뷰를 수행한다. 인증/입력 오류 감사·탐지 SEC-11은 아래7단계에서 추가 검증한다.

```powershell
.\gradlew.bat test --tests '*AccessGrantApiIntegrationTest' --tests '*AccessGrantConcurrencyIntegrationTest' --tests '*ConcurrencyIntegrationTest' --no-daemon
.\gradlew.bat clean build --no-daemon
```

감사 INSERT 실패와 지연 제약 트리거의 커밋 단계 실패 모두 변경 롤백·503을 확인한다. 임시 트리거·제약·테이블 이름 변경은 finally로 복구하며 Testcontainers DB에만 적용한다. 권한 변경 SEC-08 및 회수 SEC-07·감사 민감정보 SEC-12 검증을 포함하지만, 이6단계 테스트만으로 탐지 SEC-11이나 인증/입력 오류 감사 검증을 대신하지 않는다.

## 6. 7단계 오류 감사·탐지 검증

- RequestFailureAuditIntegrationTest: 네 금융API의 익명 인증 실패·검증된 주체 입력 오류, 요청ID일치·민감정보비노출·독립커밋·중복방지·범위밖제외. 새 감사 INSERT/지연 커밋 실패에도401/400 유지, 기존 필수 감사503 유지.
- DetectionIntegrationTest: (T-5분,T] 경계·미래제외·임계값·제외사유·주체유형분리,5분억제·조건재충족재알림·과거창제외. 실제 두DB연결이 같은 상태행에서 대기한 뒤 한 알림만 커밋하는지 확인한다. 알림INSERT/커밋 실패는 상태도롤백한다.
- DetectionApiIntegrationTest: 실제 서명JWT의 무단조회5회·계좌3개→감사→SEC-11 알림. 인증/입력 오류는 집계하지 않고 본인조회는 계속허용한다. 스케줄러 장애 후 다음실행복구·API비영향·기본작업등록·자동실행off 직접실행을 검증한다.
- 테스트프로필은 자동스케줄러만 비활성화한다. 집계·트랜잭션 검증은 실제 서비스를 호출하고 PostgreSQL을 사용한다. 고정 시간Fixture와DB상태를 제어하며 긴실시간대기나 보안검사 우회를 하지 않는다.

```powershell
.\gradlew.bat test --tests '*RequestFailureAuditIntegrationTest' --tests '*DetectionIntegrationTest' --tests '*DetectionApiIntegrationTest' --no-daemon '-Dorg.gradle.jvmargs=-Xmx512m -Djava.net.preferIPv4Stack=true'
.\gradlew.bat clean build --no-daemon '-Dorg.gradle.jvmargs=-Xmx512m -Djava.net.preferIPv4Stack=true'
```

DB시각기반 최근5분만 집계한다. 창을벗어난 늦은커밋·장기중단 후 과거 이벤트 소급처리, 운영부하·연결풀검증과 원격CI는 로컬 테스트 결과와 구분한다.

7단계 로컬 전체 검증: 기존157개+새24개, 총181개 통과(실패·오류·건너뜀0). Windows의 Gradle 소켓 환경 문제는 일회성 JAVA_TOOL_OPTIONS=-Djava.net.preferIPv4Stack=true로 처리했으며 저장소 설정이나 테스트 기대값을 완화하지 않았다. 원격CI는 미실행이다.

최종 독립 리뷰 수정 후 재검증: 전체184개 통과(기존157+오류감사12+탐지11+API/스케줄러4), 실패·오류·건너뜀0. 억제 만료 전 집계창과 인코딩 URI 감사의3개 사례는 RED→GREEN으로 확인했다. 원격CI는 미실행이다.

## 7. 개발 환경 직접 확인

[개발 환경 안내](local-development.md)는 dev 프로필로 실제 JWT·HTTP·기존 PostgreSQL을 확인하는 절차다. scripts/DevTokens.java와 scripts/dev-api.ps1만 사용하고 별도 프로세스 관리자나 데모 전용 테스트 도구는 두지 않는다. DetectionTimingTest는 기본값·환경별 설정·양수 검증을, DetectionTimingIntegrationTest는 PostgreSQL의 단축 창·억제 경계를 다룬다. 기존 DetectionIntegrationTest의5분·동시성 기대는 유지한다. 개발30초 확인으로 운영5분 정책·자동 보안 회귀·Evals를 대체하지 않는다.

2026-11-20 dev 정리본 검증: `./gradlew.bat clean build --no-daemon '-Dorg.gradle.jvmargs=-Xmx512m -Djava.net.preferIPv4Stack=true'`와 실행 한정 `JAVA_TOOL_OPTIONS=-Djava.net.preferIPv4Stack=true`로 전체189개 테스트 통과(실패·오류·건너뜀0). 기존5분·동시성·감사 실패 회귀와 새 기본/환경별 시간 설정·단축 PostgreSQL 경계 테스트를 포함한다. dev JAR의 실제 JWT Access10개 응답 및 요청별 감사, 변조JWT401·입력400 오류 감사, Detection 각5개 요청의 감사5행/3대상·억제 상태 유지·30.006048초 후 재알림을 확인했다. git diff --check, 개발 비밀 Git 제외 및 JAR 혼입 검사도 통과했다. 독립 리뷰의 Critical/Important 지적은 없었으며 문서 미검증 표시를 갱신했다. 원격CI·Evals·실제5분 대기는 이번 실행에 포함하지 않는다.
