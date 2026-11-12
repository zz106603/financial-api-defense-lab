# financial-api-defense-lab
금융 API 접근 제어 및 이상 접근 탐지·검증 실험 환경

## 개발 시작

현재는 [개발 진행 순서](docs/project-overview.md#9-개발-진행-순서)의 1단계인 프로젝트 초기화 및 개발환경 구성 단계다.

- Java 21과 Gradle Wrapper로 빌드한다.
- Spring Boot, MyBatis, PostgreSQL, Flyway 의존성을 구성한다.
- 로컬 PostgreSQL은 Docker Compose로 실행한다.
- 기본 테스트·CI, 업무 스키마·Mapper, JWT 인증과 금융 API는 후속 단계에서 구현한다.

설치 요건, 실행·종료 및 검증 명령은 [개발환경 안내](docs/development-environment.md)를 따른다.
