# financial-api-defense-lab
금융 API 접근 제어 및 이상 접근 탐지·검증 실험 환경

## 개발 시작

현재는 [개발 진행 순서](docs/project-overview.md#9-개발-진행-순서)의 3단계인 PostgreSQL 데이터 모델·MyBatis 영속성 기반까지 구현한 상태다.

- Java 21과 Gradle Wrapper로 빌드한다.
- Spring Boot, MyBatis, PostgreSQL, Flyway 의존성을 구성한다.
- 로컬 PostgreSQL은 Docker Compose로 실행한다.
- JUnit·Testcontainers로 기본 통합 테스트를 실행하고 GitHub Actions에서 빌드·테스트한다.
- Flyway로 7개 업무 테이블을 생성하고 모듈별 Java 모델·XML Mapper를 제공한다.
- 실제 PostgreSQL에서 무결성, 소유권·위임 권한별 조회 SQL, 동시 활성 권한 중복 방지와 회수 후 재조회를 검증한다.
- JWT 인증, 금융 API와 서비스 계층의 권한 변경·감사·탐지는 후속 단계다. 현재 HTTP 금융정보 엔드포인트는 없다.

설치 요건, 실행·종료 및 검증 명령은 [개발환경 안내](docs/development-environment.md)를 따른다.
테스트 범위·실행 방법과 CI 동작은 [테스트 및 CI 안내](docs/testing-ci.md)를 따른다.
