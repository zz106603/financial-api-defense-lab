# PostgreSQL 데이터 모델 및 MyBatis 구현 계획

**Goal:** 개발 순서 3단계의 영속성 기반을 구현하고 실제 PostgreSQL로 검증한다.

**Architecture:** 기존 7개 엔티티를 Flyway로 생성한다. 모듈별 Java record와 MyBatis XML Mapper를 사용하며 금융 조회 SQL 자체에 고객 소유권 또는 제휴사의 활성 개별 권한을 적용한다. HTTP API와 서비스 트랜잭션은 이후 단계다.

**Tech Stack:** Java 21, Spring Boot 4.1.2, MyBatis Starter 4.1.0, PostgreSQL 18.1, Flyway, JUnit, Testcontainers.

**Spec:** `docs/data-model.md`, `docs/architecture.md`, `docs/security-requirements.md`.

## 제약 및 범위

- 기존 설계를 재기획하지 않는다. 커밋·push·PR은 수행하지 않는다.
- UUID, 정확한 금액형, 시간대 보존형을 Java/PostgreSQL 사이에서 매핑한다.
- 조회에 사용자 입력 문자열 삽입이나 권한 캐시를 사용하지 않는다.
- 초기 합성 데이터는 테스트 Fixture로 분리해 일반 애플리케이션 기동 시 주입하지 않는다.
- 가명 참조 생성, 탐지 중복 억제, 서비스 감사 트랜잭션, JWT, API 페이지 상한·반복 DELETE 응답은 해당 후속 단계에서 확정한다.
- High 위험도로 분류한다. 기존 전체 테스트와 추가 DB 테스트를 실행한다. 병렬 구현은 하지 않고 구현·검증 후 필수 독립 리뷰 1회를 순차 수행한다.

## Review Focus

- 타인·타 계좌·타 제휴사·다른 권한·회수된 권한으로 금융정보가 조회되지 않아야 한다.
- 권한이 없는 빈 결과를 API의 허용 판정에 사용하지 않아야 한다.
- 활성 권한 중복은 거부하지만 회수 후 재부여 이력은 보존해야 한다.
- 권한 회수 SQL은 계좌·권한·고객·활성 상태를 함께 제한해야 한다.
- MyBatis 세션의 로컬 캐시가 커밋된 회수를 새 조회에서 가리지 않아야 한다.

## Task 1: 스키마 및 무결성

- [x] `src/test/java/lab/financialapi/persistence/SchemaIntegrationTest.java`와 공통 테스트 DB 기반을 작성한다. 테스트 Fixture는 고객 A/B, 계좌 A-01/A-02/B-01, 제휴사 X/Y와 초기 ACCOUNT_READ를 재현한다.
- [x] 마이그레이션 없이 테스트를 실행하고 업무 테이블 부재 실패를 확인한다.
- [x] `src/main/resources/db/migration/V1__create_financial_schema.sql`에 7개 테이블, FK, CHECK, NOT NULL, 부분 유니크·조회 인덱스를 작성한다.
- [x] 금액 NUMERIC(19,2), 시각 TIMESTAMPTZ, 문자열 TEXT 선택을 데이터 모델에 기록한다. 부여 고객과 실제 소유자의 일치는 복합 FK로도 보강한다.
- [x] 스키마 테스트: `./gradlew.bat test --tests '*SchemaIntegrationTest' --no-daemon`.

## Task 2: 모델·XML Mapper 및 인가 SQL

- [x] `PersistenceIntegrationTest`에 주체 매핑, 소유권, 권한 분리, 회수, 금액·시각 매핑, 정렬·페이지 격리, null 주체 거부 테스트를 먼저 작성하고 실행한다.
- [x] identity(Customer/Partner), account(Account/TransactionRecord), authorization(AccessGrant/Permission), audit(AuditEvent), detection(SecurityAlert)에 모델과 Mapper를 배치한다.
- [x] `resources/mappers/`에 XML을 배치하고 모든 프로필 공통 Mapper 경로·camel-case·STATEMENT 로컬 캐시 설정을 적용한다.
- [x] 고객/제휴사 금융 조회 메서드를 분리하고 SQL에 인가 조건을 포함한다. 권한 부여·회수 Mapper는 소유권 조건과 변경 행 수를 제공하며 서비스의 감사 트랜잭션을 대체하지 않는다.
- [x] 감사·탐지는 저장 Mapper만 제공한다. 감사 이벤트 수정·삭제, 무인가 금융 조회 메서드는 제공하지 않는다.
- [x] Mapper 테스트: `./gradlew.bat test --tests '*PersistenceIntegrationTest' --no-daemon`.

## Task 3: 통합 검증 및 문서

- [x] 실제 DB에서 동시 활성 권한 중복 방지와 커밋된 회수 이후 새 조회 거부를 검증한다.
- [x] README, 데이터 모델, 개발환경·테스트 문서를 현재 구현 범위에 맞춘다.
- [x] `./gradlew.bat clean build --no-daemon`과 `git diff --check`를 실행한다.
- [x] 변경 내용을 별도로 검토하고 검증 범위와 미구현 SEC 테스트 및 독립 리뷰 미수행 여부를 보고한다.

## 검증 결과

- 스키마 테스트는 구현 전 업무 테이블 부재로 10개 실패했고, V1 적용 후 10개 통과했다.
- Mapper 테스트는 인터페이스·모델 부재로 컴파일 실패를 확인한 후 구현했다.
- 회수 시각 null 테스트는 PostgreSQL의 파라미터 타입 추론 오류를 재현했고 명시적 TIMESTAMPTZ 캐스트로 수정했다.
- 캐시를 SESSION으로 일시 변경하면 회수 커밋 후 동일 세션 조회 테스트가 실패하는 것을 확인하고 STATEMENT로 복원했다.
- `.\gradlew.bat clean build --no-daemon`: 성공, 테스트 24개 / 실패 0 / 건너뜀 0, 실행 JAR 생성.
- `git diff --check`: 성공.
- 독립 읽기 전용 리뷰 1회: Critical·Important 없음. 계획 체크박스 상태 갱신 지적을 반영했다.
- Mapper 첫 실행은 테스트 통과 후 Gradle 소켓 `Bad address: listen` 오류로 프로세스가 실패했다. 스택 수집을 위한 재실행과 최종 전체 빌드에서는 재현되지 않았다. 원인은 확정하지 않았으며 제품/빌드 설정을 변경하지 않았다.
- 미실행/후속 범위: 원격 GitHub Actions, JWT·HTTP API 수준 SEC-01~12 전체 검증, 서비스 감사 트랜잭션·가명화·탐지. 현재 빈 거래 결과는 API 인가 판정으로 사용할 수 없고, 영속성 모델은 응답 DTO로 직접 노출하지 않는다.
- 작업 브랜치 `feat/postgresql-mybatis`에서 미커밋 상태로 유지한다. push·PR은 수행하지 않았다.
