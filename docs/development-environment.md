# 개발환경

## 1. 범위

[프로젝트 개발 순서](project-overview.md#9-개발-진행-순서)의 개발환경을 설명한다. 테스트·CI는 [테스트 및 CI 안내](testing-ci.md)를 따른다. 현재 5단계의 두 금융정보 GET API·객체별 인가·조회용 최소 감사까지 구현했다. 권한 변경 API·변경 감사와 탐지는 후속 단계다.

## 2. 도구 및 의존성

| 항목 | 구성 |
|---|---|
| Java | 21, Gradle Java Toolchain |
| Gradle | 8.14.3 Wrapper |
| Spring Boot | 4.1.2 |
| MyBatis Spring Boot Starter | 4.1.0 |
| PostgreSQL | 로컬 Compose 이미지 `postgres:18.1` |
| PostgreSQL JDBC·Flyway | Spring Boot BOM에서 버전 관리 |
| Docker | Docker Desktop의 Linux 엔진과 Compose v2 |

전역 Gradle 설치는 필요하지 않다. 최초 빌드 시 Wrapper 배포본과 Maven Central 의존성을 내려받으므로 네트워크가 필요하다. `JAVA_HOME`과 `PATH`가 JDK 21을 가리키도록 설정한다.

Spring Boot의 [시스템 요구사항](https://docs.spring.io/spring-boot/system-requirements.html)과 MyBatis의 [4.1.0 릴리스](https://github.com/mybatis/spring-boot-starter/releases/tag/mybatis-spring-boot-4.1.0)를 기준으로 구성한다. 프로젝트 식별자와 기본 패키지는 `lab.financialapi`를 사용한다.

## 3. 로컬 실행

저장소 루트에서 실행한다. Docker Desktop을 실행하고 Linux 엔진이 준비됐는지 먼저 확인한다.

```powershell
java -version
docker version
docker compose version
```

환경변수 예제를 복사한다. 기존 `.env`가 있다면 덮어쓰지 않고 필요한 값만 확인한다.

```powershell
Copy-Item .env.example .env
```

`.env`는 Git에서 제외된다. 예제 자격정보는 격리된 로컬 합성 데이터 개발 전용이며 다른 환경에서 재사용하지 않는다. 포트 충돌 시 `.env`의 `POSTGRES_PORT`를 변경한다. Compose와 애플리케이션의 `local` 프로필이 같은 파일을 읽으며, 애플리케이션에는 OS 환경변수로도 값을 제공할 수 있다.

JWT 검증 설정도 `.env` 또는 OS 환경변수로 제공한다. 기존 `.env`가 있다면 덮어쓰지 않고 아래 항목을 추가한다.

| 환경변수 | 용도 |
|---|---|
| JWT_ISSUER | 신뢰하는 발급자의 정확한 `iss` 값 |
| JWT_AUDIENCE | 이 API용 토큰의 `aud` 값 |
| JWT_PUBLIC_KEY_LOCATION | X.509 PEM RSA 공개키의 Spring Resource 위치(예: `file:C:/keys/issuer-public.pem`) |
| AUDIT_RESOURCE_HMAC_KEY | 계좌 감사 가명 참조용, 최소 32바이트 무작위 비밀값을 표준 Base64로 인코딩한 값 |

`.env.example`의 공개키 경로는 예시이므로 실제 신뢰하는 공개키 경로로 바꿔야 한다. RSA 2048비트 이상과 RS256을 사용한다. issuer는 클레임 비교에만 사용하며 해당 URL로 네트워크 요청을 보내지 않는다. 설정이 없거나 공개키가 잘못되면 웹 애플리케이션은 기동하지 않는다. 개인키·테스트 토큰·개발용 기본 신뢰 키를 배포 코드에 추가하지 않는다. 키 교체에는 재기동이 필요하다.

감사 HMAC 키도 반드시 제공한다. `.env.example`의 빈 값을 그대로 사용하면 기동에 실패한다. PowerShell 7에서 `[Convert]::ToBase64String([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32))`로 생성한 값을 로컬 `.env`에 보관할 수 있다. JWT 발급 개인키와는 별개의 비밀값이며 커밋하거나 로그에 출력하지 않는다. 테스트 프로필의 고정 합성 키를 일반 환경에서 재사용하지 않는다. 같은 감사 대상의 연결을 유지하려면 키를 안정적으로 관리해야 한다.

```powershell
docker compose up -d --wait postgres
.\gradlew.bat bootRun --args='--spring.profiles.active=local'
```

애플리케이션은 `127.0.0.1:8080`, PostgreSQL은 `127.0.0.1:5432`에 바인딩한다. `local` 프로필을 명시해야 로컬 DB 연결 설정을 읽는다. 일반 경로는 JWT 인증이 필요하므로 미인증 루트 요청도 401을 반환한다. 등록된 주체의 유효한 토큰으로 매핑되지 않은 경로를 요청하면 404다. Flyway가 `db/migration/V1__create_financial_schema.sql`을 적용해 7개 업무 테이블을 생성한다. 합성 데이터는 `src/test/resources/fixtures/financial-data.sql`의 테스트 전용 Fixture로 분리했으며 일반 기동 시 자동 삽입하지 않는다. 로컬에서 인증 성공을 확인하려면 별도로 준비한 발급 도구의 sub/유형과 일치하는 합성 주체 데이터가 필요하다.

MyBatis XML 경로·UUID TypeHandler·생성자 매핑과 `STATEMENT` 로컬 캐시는 공통 `application.yaml`에서 설정한다. 로컬 캐시를 `SESSION`으로 바꾸거나 권한 조회에 2차 캐시를 추가하면 회수 후 재조회 정책에 영향을 줄 수 있다.

macOS/Linux에서는 `cp .env.example .env`, `sh ./gradlew bootRun --args='--spring.profiles.active=local'`을 사용한다.

## 4. 빌드 및 환경 확인

```powershell
.\gradlew.bat --version
.\gradlew.bat clean build
docker compose config --quiet
docker compose ps
docker compose exec -T postgres pg_isready -U financial_api_lab -d financial_api_defense_lab
```

기본 계정·DB 이름을 변경했다면 `pg_isready` 인자도 변경한다. `clean build`에는 Testcontainers 기반 기본 통합 테스트가 포함되므로 Docker 엔진이 실행 중이어야 한다. 테스트 DB는 Compose의 로컬 DB와 별개로 자동 생성·정리되며, 테스트만 실행할 때는 `.env`와 Compose 기동이 필요하지 않다. 기본 통합 테스트 통과를 아직 구현하지 않은 보안 요구사항 전체의 검증 완료로 해석하지 않는다.

패키징된 JAR로도 실행할 수 있다.

```powershell
java -jar build/libs/financial-api-defense-lab-0.0.1-SNAPSHOT.jar --spring.profiles.active=local
```

## 5. 종료 및 데이터 보존

애플리케이션은 실행 터미널에서 `Ctrl+C`로 종료한다.

```powershell
docker compose stop postgres
```

컨테이너와 네트워크를 제거하려면 `docker compose down`을 사용한다. 명명된 `postgres-data` 볼륨은 유지되며, 이 안내의 명령은 DB 데이터를 삭제하지 않는다. 초기화된 볼륨이 있을 때 `.env`의 사용자·DB·비밀번호를 바꾸는 것만으로 기존 DB 계정이 변경되지는 않는다.
