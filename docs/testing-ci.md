# 기본 테스트 및 CI

## 1. 범위

[개발 순서](project-overview.md#9-개발-진행-순서)의 2~4단계로, 애플리케이션 기동과 PostgreSQL·MyBatis 및 JWT 인증·DB 주체 매핑을 검증하고 같은 검증을 GitHub Actions에서 실행한다.

| 대상 | 현재 검증 |
|---|---|
| PostgreSQL·MyBatis·Flyway | 앱의 SqlSessionFactory로 격리된 PostgreSQL에 연결하고 Flyway 이력 테이블·검증 결과 확인 |
| 웹 기동 | 임의 포트로 실제 서버를 실행하고 유효한 서명 토큰으로 미매핑 경로의 HTTP 404 확인 |
| 업무 스키마 | 7개 테이블, 필수 값·FK·소유자 복합 FK·권한 CHECK·활성 권한 중복·회수 이력·감사 주체·탐지 창 제약 |
| XML Mapper | sub별 주체 매핑, UUID·금액·시각·enum 매핑, 고객 소유권·제휴사별 계좌/권한 범위·회수 상태, 거래 정렬/페이지 격리, 감사·탐지 저장 |
| DB 경합·가시성 | 독립 트랜잭션의 동시 활성 권한 부여 중 1건만 커밋, 회수 커밋 후 동일 MyBatis 세션의 새 조회 거부 |
| JWT·요청 주체 | 실제 RSA 서명·변조·알고리즘·클레임 검증, PostgreSQL 주체 매핑·추가 ID/scope 무시·재식별, Bearer 전용/Stateless, 401/403/503 비노출 오류 |
| 인증 설정 | 필수 값 누락, 잘못된 PEM·없는 파일·2048비트 미만 RSA 키 거부 |
| 빌드 | Java 21 컴파일, 테스트, 실행 JAR 패키징 |

형식적인 모델 getter 단위 테스트는 추가하지 않는다. 현재 `ApplicationIntegrationTest` 2개, `SchemaIntegrationTest` 10개, `PersistenceIntegrationTest` 10개, `ConcurrencyIntegrationTest` 2개, `JwtAuthenticationIntegrationTest` 44개, `JwtConfigurationTest` 8개로 총 76개 테스트를 구성한다. 매개변수화 테스트는 각 입력 사례를 한 개로 계산한다.

DB 계층에서 SEC-02~08·10의 관련 소유권/권한 범위를 일부 검증한다. 인증 테스트는 SEC-01·10의 필터 체인 거부 및 실제 JWT 서명/클레임 검증을 추가로 확인하지만 금융정보 HTTP API·서비스 계층이 없으므로 SEC-01~12 전체 보안 회귀 검증을 완료한 것은 아니다. API 응답 필드 차단(SEC-09), 탐지 실행(SEC-11), 감사 민감정보 차단(SEC-12), 권한 변경·감사 원자성 및 거부 감사의 독립 저장은 후속 단계다. 감사 저장 테스트는 전달된 구조화 필드의 저장만 확인하며 가명화·민감정보 필터를 검증하지 않는다.

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

영속성 테스트 3개 클래스는 하나의 JVM 수명 PostgreSQL 컨테이너와 Spring 컨텍스트를 공유하고 Ryuk이 컨테이너를 정리한다. 스키마·Mapper 테스트는 테스트 트랜잭션으로 롤백한다. 경합 테스트는 실제 커밋이 필요하므로 테스트 전후 테스트 DB만 비우고 고정 Fixture를 다시 삽입한다. 병렬 테스트 실행은 설정하지 않는다. `@Sql`의 정리 스크립트는 일반 애플리케이션 DB에 실행하면 안 된다.

```powershell
.\gradlew.bat test --tests '*SchemaIntegrationTest' --no-daemon
.\gradlew.bat test --tests '*PersistenceIntegrationTest' --tests '*ConcurrencyIntegrationTest' --no-daemon
.\gradlew.bat test --tests '*JwtAuthenticationIntegrationTest' --tests '*JwtConfigurationTest' --no-daemon
```

회수 가시성 테스트는 `READ COMMITTED` 및 같은 SqlSession의 반복 조회를 사용한다. `SESSION` 캐시로 변경하면 이전 허용 결과가 재사용되어 실패하므로, 기본 캐시로 정책이 퇴행하는 경우도 탐지한다. 현재 테스트는 Mapper 수준이며 회수 HTTP 성공 응답과 API 요청의 시간 순서까지 검증하지는 않는다.

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
