# financial-api-defense-lab
금융 API 접근 제어 및 이상 접근 탐지·검증 실험 환경

## 개발 시작

현재는 [개발 진행 순서](docs/project-overview.md#9-개발-진행-순서)의 5단계인 금융정보 조회 API·객체별 인가와 조회용 최소 감사까지 구현한 상태다.

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
- 권한 부여·회수 API와 변경 감사, 이상 접근 탐지 및 실습용 토큰 발급·요청 도구는 후속 단계다.

설치 요건, 실행·종료 및 검증 명령은 [개발환경 안내](docs/development-environment.md)를 따른다.
테스트 범위·실행 방법과 CI 동작은 [테스트 및 CI 안내](docs/testing-ci.md)를 따른다.
