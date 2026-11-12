# financial-api-defense-lab
금융 API 접근 제어 및 이상 접근 탐지·검증 실험 환경

## 개발 시작

현재는 [개발 진행 순서](docs/project-overview.md#9-개발-진행-순서)의 1~2단계인 프로젝트 초기화·개발환경 및 기본 테스트·CI를 구성한 상태다.

- Java 21과 Gradle Wrapper로 빌드한다.
- Spring Boot, MyBatis, PostgreSQL, Flyway 의존성을 구성한다.
- 로컬 PostgreSQL은 Docker Compose로 실행한다.
- JUnit·Testcontainers로 기본 통합 테스트를 실행하고 GitHub Actions에서 빌드·테스트한다.
- 업무 스키마·Mapper, JWT 인증과 금융 API는 후속 단계에서 구현한다.

설치 요건, 실행·종료 및 검증 명령은 [개발환경 안내](docs/development-environment.md)를 따른다.
테스트 범위·실행 방법과 CI 동작은 [테스트 및 CI 안내](docs/testing-ci.md)를 따른다.
