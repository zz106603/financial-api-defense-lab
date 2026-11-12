# 기본 테스트 및 CI

## 1. 범위

[개발 순서](project-overview.md#9-개발-진행-순서)의 2단계로, 초기 애플리케이션의 기동과 데이터 접근 기반을 검증하고 같은 검증을 GitHub Actions에서 실행한다.

| 대상 | 현재 검증 |
|---|---|
| PostgreSQL·MyBatis·Flyway | 앱의 SqlSessionFactory로 격리된 PostgreSQL에 연결하고 Flyway 이력 테이블·검증 결과 확인 |
| 웹 기동 | 임의 포트로 실제 서버를 실행하고 미매핑 경로의 HTTP 404 확인 |
| 빌드 | Java 21 컴파일, 테스트, 실행 JAR 패키징 |

업무 로직이 아직 없어 형식적인 단위 테스트는 추가하지 않는다. 현재 테스트는 `ApplicationIntegrationTest`의 기본 통합 테스트 2개이며, JWT 인증·객체별 인가·권한 변경·감사·탐지의 SEC-01~12 보안 회귀 테스트는 아직 구현되지 않았다. 해당 기능을 구현하면서 [보안 요구사항](security-requirements.md#6-필수-보안-테스트)에 따른 단위·통합 테스트를 추가한다. JWT 모의 인증과 실제 서명 검증은 후속 인증 테스트에서 구분한다.

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
