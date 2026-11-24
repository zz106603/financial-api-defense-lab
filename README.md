# financial-api-defense-lab
금융 API 접근 제어 및 이상 접근 탐지·검증 실험 환경

## 개발 시작

현재는 금융정보 조회·권한 부여/회수, 감사·R-01 탐지와 결함 주입 Evals4개를 구현하고 원격 CI·Evals 실행까지 확인한 상태다. [MVP 검증 현황과 남은 범위](docs/mvp-status.md)에 따라 Agent 작업 효율 측정 등 미완료 항목을 별도로 관리한다.

- Java 21과 Gradle Wrapper로 빌드한다.
- Spring Boot, MyBatis, PostgreSQL, Flyway 의존성을 구성한다.
- 로컬 PostgreSQL은 Docker Compose로 실행한다.
- JUnit·Testcontainers로 기본 통합 테스트를 실행하고 GitHub Actions에서 빌드·테스트한다.
- Flyway로 7개 업무 테이블을 생성하고 모듈별 Java 모델·XML Mapper를 제공한다.
- 실제 PostgreSQL에서 무결성, 소유권·위임 권한별 조회 SQL, 동시 활성 권한 중복 방지와 회수 후 재조회를 검증한다.
- Spring Security Resource Server로 RS256 JWT를 검증하고 고객·제휴사의 DB UUID와 유형을 요청 주체로 전달한다.
- `GET /api/v1/accounts/{accountId}`와 `/transactions`에서 고객 소유권 또는 제휴사의 개별 활성 권한을 조회 SQL에서 검증한다.
- 조회 허용·거부를 응답 전에 별도 트랜잭션으로 감사하고, 감사 저장 실패 시 금융정보를 반환하지 않는다.
- 기동 시 RSA 공개키·JWT issuer/audience와 감사 가명화용 외부 HMAC 비밀키가 필요하다. 개인키·토큰 발급 API는 포함하지 않는다.
- 권한 변경과 성공 감사는 원자적으로 처리하고, 접근 거부 감사는 독립 저장한다. 기존 필수 감사 실패는503이며 새 인증/입력 오류 감사 실패는401/400을 유지한다.
- R-01은30초마다 최근5분의 객체별 권한 위반을 집계하고5회·3계좌 이상이면 알림을 생성한다. 동일 주체·규칙은5분 억제 후 조건 재충족 시 재알림하며 자동 차단하지 않는다.
- 개발 프로필의 JWT 발급·API 호출 도구와 격리된 [보안 Evals](docs/security-evals.md)를 제공한다.

설치 요건, 실행·종료 및 검증 명령은 [개발환경 안내](docs/development-environment.md)를 따른다.
테스트 범위·실행 방법과 CI 동작은 [테스트 및 CI 안내](docs/testing-ci.md)를 따른다.

## 로컬 API 확인

Evals 전에 application-dev.yaml과 기존 Compose PostgreSQL로 실제 JWT·API·DB를 확인한다. 기존 테스트 Fixture를 재사용하며 프런트엔드·관리 API는 추가하지 않는다. [개발 환경 설정과 실행 순서](docs/local-development.md)를 따른다. 기본 보안 정책은5분을 유지하고 dev에서만30초로 단축한다.

```powershell
java scripts/DevTokens.java init .local-dev
# 위 안내에 따라 환경변수 설정·bootRun·Fixture 적용 후 다른 터미널에서 실행
.\scripts\dev-api.ps1 Access
.\scripts\dev-api.ps1 Detection
```

## 보안 Evals

기존 보안 테스트로 정상 기준선과 소유권·위임 계좌·회수·응답 필드 결함4개를 평가한다. 제품 코드와 분리된 임시 복사본에서만 주입하며 결과는 build/security-evals에 남긴다.

```powershell
python -m unittest discover -s scripts/tests -p test_security_evals.py
python scripts/security_evals.py
```
