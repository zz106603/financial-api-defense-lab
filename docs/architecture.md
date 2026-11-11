# Architecture

## 1. 설계 원칙

금융 API의 보안 정확성, 검증 가능성, 구조 단순성을 우선한다.

- 단일 애플리케이션 내에서 책임별 모듈 분리
- 인증과 업무 인가 분리
- 금융 데이터 조회 SQL에 실제 인가 조건 적용
- 권한 변경과 감사 기록의 트랜잭션 일관성 확보
- 보안 코드 변경에 대한 자동 회귀 검증

## 2. 기술 스택

| 구분 | 선정 기술 | 이유 |
|---|---|---|
| Language | Java 21 | LTS, 기존 경험 활용 |
| Framework | Spring Boot 4.1.2 | 신규 프로젝트 기반 |
| Security | Spring Security | 인증 및 API 보안 |
| Authentication | OAuth2 Resource Server + JWT | 제휴사 API 요청 주체 검증 |
| Authorization | 자체 Authorization Service | 금융 업무별 인가 정책 관리 |
| Database | PostgreSQL | 권한 관계·트랜잭션 처리 |
| Data Access | MyBatis + XML Mapper | 인가 SQL 명시적 관리·리뷰 |
| Migration | Flyway | 스키마 이력 관리 |
| Build | Gradle | 빌드·의존성 관리 |
| Testing | JUnit, Spring Security Test, MockMvc, Testcontainers | 보안·DB 통합 검증 |
| CI | GitHub Actions | 자동 테스트 및 보안 검사 |

실제 개발 시작 시 Spring Boot와 MyBatis Starter 등 의존성 호환성을 확인한다.

## 3. 시스템 구조

**Modular Monolith:** 단일 Spring Boot 애플리케이션과 PostgreSQL을 사용한다.

```text
Customer / Partner
        |
Spring Security (JWT 검증)
        |
Financial API
        |
Authorization Service
        |
MyBatis (인가 조건 포함 SQL)
        |
PostgreSQL

Audit Service ----> AuditEvent
Detection Service -> SecurityAlert
```

### 모듈 책임

| 모듈 | 책임 |
|---|---|
| identity | 인증 주체 식별 및 고객·제휴사 매핑 |
| account | 금융정보 조회 |
| authorization | 소유권·위임 권한·조회 범위 정책 |
| audit | 접근·권한 변경 감사 기록 |
| detection | 감사 이벤트 분석 및 이상 접근 탐지 |
| common | 공통 오류 및 기술 기반 구성 |

## 4. 인증 설계

Spring Security OAuth2 Resource Server로 JWT를 검증한다. API 서버에는 토큰 발급 기능을 두지 않는다.

검증 항목:

- 서명 및 허용된 알고리즘
- `iss`, `aud`, `exp`, `nbf`
- `sub`, `principal_type` (CUSTOMER / PARTNER)

검증된 주체를 DB의 고객 또는 제휴사에 매핑한다. 클라이언트가 제공한 주체 ID를 신뢰하지 않는다.

JWT에는 계좌별 조회 권한을 저장하지 않으며, 매 요청에서 DB의 현재 권한을 확인한다.

개발·테스트용 토큰은 분리된 발급 도구 또는 Fixture로 생성한다. JWT 서명 검증을 실제로 수행하는 통합 테스트를 포함하며, 발급용 개인키와 개발용 토큰 생성 기능은 배포 API에 포함하지 않는다.

MVP에서는 토큰 자체의 즉시 폐기 기능을 구현하지 않는다.

## 5. 인가 및 금융정보 조회

Authorization Service가 요청 주체·대상 계좌·조회 행위에 대한 정책을 관리한다.

실제 조회는 MyBatis SQL에 인가 조건을 포함한다.

- CUSTOMER: 계좌 소유권 확인
- PARTNER: 계좌·제휴사·필요 권한·미회수 상태 확인
- 그 외 또는 검증 불가: 거부

인가 확인과 데이터 조회 사이의 권한 변경 위험을 줄이기 위해 금융정보 조회 SQL에서 권한 조건을 함께 평가한다.

조회 경로의 불필요한 사전 권한 조회와 정책 중복 구현은 피한다. 데이터 조회 결과가 없을 때 외부에는 객체 존재 여부를 노출하지 않는다.

### MyBatis 원칙

- XML Mapper에서 SQL을 관리한다.
- 사용자 입력에는 `#{}` 파라미터 바인딩을 사용한다.
- 검증되지 않은 입력의 `${}` 삽입을 금지한다.
- 인가 조건이 포함된 SQL은 실제 PostgreSQL로 검증한다.
- 인가 SQL 변경을 High 위험도 작업으로 취급한다.

## 6. 트랜잭션 설계

### 권한 부여·회수

계좌 소유권 검증, AccessGrant 변경, 감사 이벤트 저장을 하나의 트랜잭션으로 처리한다.

하나라도 실패하면 전체 변경을 롤백한다. 권한 회수 성공 응답은 커밋 이후 반환한다.

동일한 활성 권한의 중복 생성은 PostgreSQL 부분 유니크 인덱스로 방지한다.

### 권한 회수와 조회의 동시성

PostgreSQL 기본 `READ COMMITTED`를 사용하고 매 조회 SQL에서 현재 권한을 확인한다.

- 회수 커밋 이후 시작한 조회 SQL은 회수된 권한을 허용하지 않는다.
- 회수 커밋 이전에 시작한 조회는 기존 권한으로 완료될 수 있다.
- 이미 실행 중인 조회를 강제로 중단하지 않는다.

동시성 테스트로 INV-04 충족 여부를 확인한다.

### 감사 이벤트 트랜잭션

| 상황 | 처리 |
|---|---|
| 권한 부여·회수 | 업무 변경과 동일 트랜잭션 |
| 정상 금융정보 조회 | 조회 성공 후 응답 전에 동기 저장 |
| 접근 거부 | 실패한 업무 트랜잭션과 분리해 저장 |
| 필수 감사 저장 실패 | 금융정보 반환 중단·오류 처리 |

거부 이벤트는 업무 롤백에도 유지되도록 별도 Audit Service의 트랜잭션 경계에서 처리한다.

감사 저장소 장애로 기록을 남길 수 없으면 운영 오류를 기록하고 접근은 허용하지 않는다. 저장소 장애 시 감사 기록의 완전한 보존은 보장하지 않는다.

## 7. 이상 접근 탐지

AuditEvent를 주기적으로 조회하는 규칙 기반 탐지를 사용한다.

**R-01 초기 규칙**

| 항목 | 기준 |
|---|---|
| 대상 | 인증된 동일 주체의 객체별 권한 위반 |
| 기간 | 최근 5분 |
| 위반 횟수 | 5회 이상 |
| 서로 다른 계좌 | 3개 이상 |
| 실행 주기 | 30초 |
| 결과 | SecurityAlert 생성 |

인증 실패와 입력 형식 오류는 R-01 집계에서 제외한다.

외부에는 동일한 404를 반환하더라도 내부에서는 확인된 권한 위반과 단순 객체 미존재를 구분해 탐지한다.

동일 주체·규칙에 대한 중복 알림을 억제한다. 정확한 중복 처리 방식은 구현 시 검증한다.

감사 기록과 탐지 처리를 분리해 탐지 실패가 접근 제어 결과에 영향을 주지 않도록 한다.

## 8. 테스트 및 CI

| 검증 | 도구·방식 |
|---|---|
| 정책 단위 테스트 | JUnit |
| JWT 및 API 보안 | Spring Security Test, MockMvc |
| SQL·트랜잭션 | Testcontainers + PostgreSQL |
| 동시성 | 실제 DB 기반 통합 테스트 |
| 결함 탐지 | 격리된 결함 주입 Evals |
| Agent 문제 해결 | Codex 분석·수정·재검증 평가 |

CI에서는 빌드, 단위 테스트, 보안 통합 테스트를 실행한다.

Codex 작업은 Low/Medium/High로 분류한다. High 변경은 전체 보안 회귀 테스트와 독립 리뷰를 요구한다.

위험도별 필수 검증은 Agent의 자율 판단에만 맡기지 않고 CI에서도 강제할 수 있도록 설계한다.

SAST·의존성 검사는 단계적으로 추가하며 결함 주입 Evals는 정상 제품 빌드에서 분리한다.

## 9. 제외한 대안

| 기술 | 제외 이유 |
|---|---|
| MSA, Kafka/RabbitMQ | 현재 규모에서 불필요한 분산 처리 |
| Redis | 권한 캐시 없이 DB에서 직접 검증 |
| Keycloak | 별도 인증 서버 운영 범위 확대 방지 |
| OpenFGA | 현재 권한 관계는 자체 인가로 처리 가능 |
| JPA/JdbcClient | 명시적인 SQL 관리에 MyBatis가 적합 |
| Elasticsearch | PostgreSQL로 초기 감사·탐지 처리 가능 |
| ML 탐지·프런트엔드 | 핵심 보안 검증에 필수 아님 |

## 10. 후속 구현 결정

- [JWT 테스트 키·발급 도구 구성](#4-인증-설계)
- [인가 정책과 Mapper의 구체적인 연결 방식](#5-인가-및-금융정보-조회)
- [SQL·트랜잭션 경계의 실제 동시성 검증](#권한-회수와-조회의-동시성)
- [감사 기록 실패 및 오류 처리 세부 구현](#감사-이벤트-트랜잭션)
- [탐지 중복 알림 억제](#7-이상-접근-탐지)
- [Codex 하네스 및 CI의 구체적인 실행 절차](#8-테스트-및-ci)

## 11. 관련 문서

- [보안 요구사항](security-requirements.md)
- [데이터 모델](data-model.md)
- [API 명세](api-spec.md)
