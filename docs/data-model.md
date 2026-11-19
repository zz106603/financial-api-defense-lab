# Data Model

## 1. 목적

금융정보 조회, 계좌별 위임 권한, 감사 기록 및 이상 접근 탐지에 필요한 PostgreSQL 데이터 구조를 정의한다.

모든 금융 데이터는 합성 데이터다. 내부 식별자는 UUID를 사용한다.

## 2. 엔티티 관계

```text
Customer 1 ── N Account
                  |
                  +── N TransactionRecord
                  |
                  +── N AccessGrant N ── 1 Partner

AuditEvent → 접근·권한 변경 이력
SecurityAlert → AuditEvent 분석 결과
```

7개 업무 엔티티와1개 내부 탐지 상태 테이블을 사용한다.

## 3. 주요 테이블

컬럼 표는 논리 모델이며 `—`는 아래 구현 규칙을 적용한다. 실제 SQL 타입·제약은 `src/main/resources/db/migration/V1__create_financial_schema.sql`과 `V2__create_detection_state.sql`을 기준으로 한다.

### 3.1 customer

| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| id | UUID | PK | — |
| auth_subject | — | UNIQUE, NOT NULL | JWT sub 매핑 |
| name | — | NOT NULL | 테스트용 고객명 |
| created_at | — | NOT NULL | 생성 시각 |

### 3.2 partner

| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| id | UUID | PK | — |
| auth_subject | — | UNIQUE, NOT NULL | JWT sub 매핑 |
| name | — | NOT NULL | 테스트용 제휴사명 |
| created_at | — | NOT NULL | 생성 시각 |

인증 시 JWT의 `principal_type`과 `sub`를 사용해 각 테이블의 주체를 식별한다.

### 3.3 account

| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| id | UUID | PK | — |
| customer_id | — | customer FK, NOT NULL | — |
| bank_name | — | — | 테스트용 금융기관명 |
| masked_account_number | — | — | 마스킹된 계좌번호 |
| balance | — | — | 합성 잔액 |
| created_at | — | — | 생성 시각 |

한 고객은 여러 계좌를 소유할 수 있다.

### 3.4 transaction_record

| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| id | UUID | PK | — |
| account_id | — | account FK, NOT NULL | — |
| occurred_at | — | — | 거래 시각 |
| amount | — | — | 합성 거래금액 |
| description | — | — | 테스트용 거래 설명 |

거래내역은 Account에 종속된다. 거래 생성·수정·삭제 API는 구현하지 않는다.

금액은 부동소수점 오차를 피할 수 있는 정확한 숫자형으로 관리한다.

### 3.5 access_grant

| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| id | UUID | PK | — |
| account_id | — | account FK, NOT NULL | — |
| partner_id | — | partner FK, NOT NULL | — |
| permission | — | — | ACCOUNT_READ / TRANSACTION_READ |
| granted_by | — | customer FK, NOT NULL | — |
| granted_at | — | NOT NULL | 부여 시각 |
| revoked_at | — | NULL 허용 | 회수 시각 |

#### 권한 상태

| 상태 | 조건 |
|---|---|
| ACTIVE | revoked_at IS NULL |
| REVOKED | revoked_at IS NOT NULL |

권한 종류마다 별도 레코드를 생성하며, 회수 시 삭제하지 않고 `revoked_at`을 설정한다.

회수된 권한을 다시 부여하면 새 레코드를 생성한다.

#### 중복 활성 권한 방지

동일한 계좌·제휴사·권한 종류의 활성 레코드는 하나만 존재할 수 있다.

```sql
CREATE UNIQUE INDEX uq_active_access_grant
ON access_grant (account_id, partner_id, permission)
WHERE revoked_at IS NULL;
```

추가 제약:

- permission은 정의된 값만 허용
- revoked_at은 granted_at보다 이전일 수 없음
- 부여 고객은 실제 계좌 소유자여야 함
- 계좌·제휴사·고객 참조 무결성 유지

소유권 검증은 서비스 계층에서 수행하며 DB 제약과 통합 테스트를 함께 적용한다.

### 3.6 audit_event

| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| id | UUID | PK | — |
| occurred_at | — | — | 이벤트 발생 시각 |
| principal_type | — | — | CUSTOMER / PARTNER / ANONYMOUS |
| principal_id | — | — | 검증된 주체 ID, 익명인 경우 NULL |
| action | — | — | 조회 또는 권한 변경 행위 |
| resource_ref | — | — | 대상 계좌의 안정적인 가명 참조값 |
| result | — | — | ALLOWED / DENIED |
| reason_code | — | — | 내부 판단 사유 코드 |
| request_id | — | — | 요청 추적 ID |

감사 대상:

- 금융정보 조회 허용·거부
- 조회 권한 부여·회수

보안 원칙:

- 인증 토큰, 비밀번호, 계좌번호 및 거래내역 원문 저장 금지
- 인증되지 않은 요청은 검증되지 않은 ID를 주체로 사용하지 않음
- 외부에는 동일한 오류를 반환하더라도 내부 사유는 구분 가능
- 일반 업무 기능에서 감사 이벤트 수정·삭제 금지

`resource_ref`는 반복 접근 대상 식별에 사용한다. 5단계 조회 감사의 HMAC 가명 참조는 아래 [조회 감사와 가명 참조](#43-조회-감사와-가명-참조)를 따른다.

### 3.7 security_alert

| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| id | UUID | PK | — |
| rule_id | — | — | 탐지 규칙 ID |
| principal_type | — | — | 탐지 대상 주체 유형 |
| principal_id | — | — | 탐지 대상 ID |
| window_start | — | — | 분석 시작 시각 |
| window_end | — | — | 분석 종료 시각 |
| violation_count | — | — | 권한 위반 횟수 |
| distinct_resource_count | — | — | 서로 다른 접근 대상 수 |
| detected_at | — | — | 탐지 시각 |

MVP에서는 R-01 탐지 결과를 저장한다.

동일 주체 유형·UUID·규칙은 성공 알림의 detected_at 기준5분 동안 억제한다. 이후 최신5분 조건을 다시 만족하면 새 알림을 생성한다. window_start는 제외·window_end는 포함하며 정확히5분 차이다.

### 3.8 detection_state

V2가 추가하는 내부 상태 테이블이며 일반 API에 노출하지 않는다.

| 컬럼 | 타입·제약 | 의미 |
|---|---|---|
| rule_id | TEXT, 복합PK | 탐지 규칙 |
| principal_type | TEXT, 복합PK, CUSTOMER/PARTNER CHECK | 주체 유형 |
| principal_id | UUID, 복합PK | 검증된 주체 UUID |
| last_alert_at | TIMESTAMPTZ, NOT NULL | 마지막 성공 알림에 사용한 DB 선점시각 |
| last_window_end | TIMESTAMPTZ, NOT NULL | 마지막 알림 집계창 끝T |

조건부UPSERT가 DB 현재시각과 이번 집계창T 모두 last_alert_at에서5분 이상 경과하고 last_window_end가 증가했는지 확인해 경쟁 실행을 제어한다. 알림INSERT와 같은 트랜잭션에 참여하므로 실패하면 억제 상태도 롤백한다. 다형적 주체ID에 개별 고객/제휴사 FK를 두지 않는 것은 security_alert와 같다.

## 4. 데이터 무결성 및 인덱스

- 모든 참조 관계에 외래키 적용
- 필수 컬럼에 NOT NULL 적용
- permission 등 제한된 값에 CHECK 제약 적용
- 활성 AccessGrant의 부분 유니크 인덱스 적용
- 거래내역의 계좌별 조회 및 정렬에 필요한 인덱스 구성
- 감사 이벤트의 주체·발생 시각·판단 사유 기반 탐지 조회 지원

### 4.1 3단계 구현 규칙

- 모든 식별자·FK는 `UUID` / Java `UUID`다. ID는 호출 측에서 생성하며 DB 기본 생성값을 두지 않는다.
- 잔액과 거래금액은 `NUMERIC(19,2)` / `BigDecimal`로 소수 둘째 자리까지 저장한다. 출금은 음수 거래금액으로 표현하며 잔액의 부호는 제한하지 않는다. 조회 API도 BigDecimal을 그대로 JSON 숫자로 반환하며, 향후 쓰기 입력의 정밀도 검증은 별도 결정한다.
- 모든 시각은 `TIMESTAMPTZ` / `OffsetDateTime`이다. 같은 순간을 보존하며 입력의 원래 지역·UTC 오프셋 자체를 보존하지는 않는다. PostgreSQL의 마이크로초 정밀도를 따른다.
- 이름·인증 sub·기관명·마스킹 계좌번호·설명·권한·감사/탐지 코드·가명 참조·추적 ID는 `TEXT`다. 임의 길이 상한은 두지 않으며 API 입력 검증을 대체하지 않는다.
- `access_grant.revoked_at`, `audit_event.principal_id`, `audit_event.resource_ref` 외 모든 업무 컬럼은 `NOT NULL`이다. 인증 전 요청·대상 없는 이벤트를 위해 가명 참조는 nullable이다.
- `account(id, customer_id)`의 복합 유니크 키를 `access_grant(account_id, granted_by)`가 참조한다. 서비스 소유권 검증과 별도로 잘못된 부여자 저장을 DB에서도 차단한다.
- FK는 기본 `NO ACTION`으로 참조 데이터를 보존한다. 감사·탐지의 다형적 주체 ID는 특정 주체 테이블 FK를 두지 않는다. 감사는 익명 유형과 null 주체 ID의 일치 여부를 CHECK로 검증한다.
- 거래 정렬은 `occurred_at DESC, id DESC`이며 `(account_id, occurred_at DESC, id DESC)` 인덱스를 사용한다. 계좌 소유자·권한 참조 FK와 감사 주체/발생시각/사유 및 알림 주체/규칙/탐지시각 인덱스를 둔다.
- 탐지 창은 종료가 시작 이상이어야 하고 횟수는 음수가 될 수 없으며 서로 다른 대상 수는 위반 횟수 이하다. 중복 억제는3.8의 상태PK·조건부UPSERT·알림과의 동일 트랜잭션으로 구현한다.

### 4.2 MyBatis 영속성 범위

- 모듈별 Java record와 `resources/mappers/` XML을 사용한다. UUID TypeHandler와 생성자 인자 이름 기반 매핑을 공통 설정으로 등록한다.
- 고객·제휴사 Mapper는 각 테이블의 `auth_subject`로 주체를 조회한다. JWT 검증 후 `JwtPrincipalConverter`가 유형에 맞는 Mapper를 선택한다.
- 계좌·거래 조회 메서드는 고객용과 제휴사용으로 분리한다. 소유권 또는 계좌·제휴사·개별 권한·미회수 조건을 실제 조회 SQL에서 평가한다. 인증되지 않은 ID를 Mapper에 전달해서는 안 된다.
- 기존 거래 목록 메서드의 빈 결과는 허용 판정으로 사용하지 않는다. 5단계 API는 별도 `readAccount/readTransactionPage` 투영으로 존재/인가 상태를 같은 SQL에서 평가한다. 거부 시 금융 필드를 반환하지 않고, 허용된 빈 페이지는 계좌 인가 상태를 유지한다. 주체 유형을 검증할 수 없으면 SQL도 FALSE 분기로 거부한다.
- `local-cache-scope: STATEMENT`를 사용하고 2차 캐시는 선언하지 않는다. 동일 세션의 반복 조회도 DB의 새 스냅샷에서 권한을 확인한다.
- 권한 삽입·회수는 소유권 조건을 포함하며 변경 행 수를 반환한다. 회수는 계좌·권한 ID·소유자·활성 상태를 모두 제한하고, 회수 시각이 없으면 변경하지 않는다. 이미 회수된 경우 0행이다. 서비스가 소유자 조건으로 다시 조회해 이미 회수된 해당 계좌의 권한임을 확인하면 HTTP 204를 반환하고 기존 revoked_at을 보존한다. 미존재/무권한은 404다.
- 감사·알림 이력 Mapper는 삽입만 제공하며, 별도 DetectionMapper가 집계와 억제 상태 조건부UPSERT를 제공한다. 5단계에서 금융 조회에 필요한 가명 참조·최소 감사 경계를 구현했다. 6단계에서 권한 변경·감사의 원자성과 독립 거부 감사를 구현했다. 7단계에서 인증/입력 오류 감사와 탐지 집계·중복 억제를 추가했다.
- 영속성 모델은 HTTP 응답 모델이 아니다. 내부 소유자 ID와 생성 시각 등을 API에서 직접 직렬화하지 않는다.

### 4.3 조회 감사와 가명 참조

- `ResourceReferenceGenerator`가 `account:` + 표준 계좌 UUID 문자열에 HMAC-SHA256을 적용하고 `account:v1:` + 소문자 64자리 hex를 저장한다. [Java 21 Mac](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/javax/crypto/Mac.html)을 사용하며 동시 요청마다 새 Mac 객체를 생성한다.
- 외부 `AUDIT_RESOURCE_HMAC_KEY`는 최소 32바이트의 무작위 비밀값을 표준 Base64로 인코딩한 값이다. 누락·잘못된 Base64·짧은 키는 기동을 실패시킨다. 일반 코드에 기본 키를 두지 않고, 테스트 프로필에만 고정 합성 키를 제공한다.
- 같은 키·계좌는 요청 주체와 결과에 관계없이 같은 가명이다. 다른 계좌나 다른 키는 다른 가명을 만든다. 키를 바꾸면 기존 참조와 연결되지 않으므로 분석 창·이력 연결을 고려해 운영에서 변경해야 한다. 자동 키 회전·이전 키 연계는 구현하지 않는다.
- 감사 action은 ACCOUNT_READ / TRANSACTION_READ, result는 ALLOWED / DENIED, reason_code는 ACCESS_ALLOWED / OBJECT_ACCESS_DENIED / RESOURCE_NOT_FOUND다. 객체 미존재와 권한 위반은 SQL의 같은 스냅샷에서 구분한다.
- 감사 서비스는 금융 응답 객체·토큰을 인자로 받지 않는다. 검증된 주체·행위·가명 대상·시각·결과·사유·요청 ID만 저장한다. 조회 감사는 독립 트랜잭션으로 응답 전에 커밋한다.

### 4.4 권한 변경 감사

권한 변경 감사도 4.3의 HMAC 가명 참조·검증된 주체·서버 생성 요청 ID를 사용하며 토큰·금융 원문을 전달하지 않는다.

| action | result | reason_code |
|---|---|---|
| GRANT_CREATE | ALLOWED | GRANT_CREATED |
| GRANT_REVOKE | ALLOWED | GRANT_REVOKED / GRANT_ALREADY_REVOKED |
| GRANT_CREATE / GRANT_REVOKE | DENIED | OBJECT_ACCESS_DENIED / RESOURCE_NOT_FOUND / PRINCIPAL_TYPE_DENIED |
| GRANT_CREATE | DENIED | ACTIVE_GRANT_CONFLICT |

실제 회수 성공과 이미 회수된 상태의 반복 요청은 감사 사유로 구분한다. 반복 요청도 감사는 새 이벤트로 기록하지만 권한 레코드·회수 시각을 수정하지 않는다. ACTIVE_GRANT_CONFLICT와 PRINCIPAL_TYPE_DENIED는 객체별 권한 위반인 OBJECT_ACCESS_DENIED와 구분해 후속 탐지에서 잘못 집계하지 않는다.

### 4.5 새 오류 감사와 탐지 대상

새 오류 감사의 action은 요청 대상의 ACCOUNT_READ/TRANSACTION_READ/GRANT_CREATE/GRANT_REVOKE, result는DENIED, reason_code는AUTHENTICATION_FAILED 또는INVALID_REQUEST다. 인증 실패는ANONYMOUS/null이며 입력 오류는 검증된 주체 또는 익명이다. 잘못된 대상UUID는resource_ref=null이다. 이 이벤트는R-01에 포함하지 않는다.

R-01은 명시한 네action의 DENIED/OBJECT_ACCESS_DENIED만 유형·UUID별로 집계하고, 가명 대상이 없는 행은 제외한다. V2의 부분 인덱스가 해당 이벤트의 시간 범위 조회를 지원한다.

## 5. 권한 데이터 처리

### 부여

1. 계좌 소유권 및 대상 제휴사 확인
2. AccessGrant 신규 생성
3. 감사 이벤트 저장
4. 트랜잭션 커밋

동시 부여 요청에서 기존 uq_active_access_grant 부분 유니크 인덱스로 중복 활성 권한을 방지한다. 해당 인덱스 충돌만 409로 처리하며 별도 명시적 lock은 사용하지 않는다.

### 회수

1. 계좌 소유권 및 AccessGrant 관계 확인
2. 활성 레코드의 revoked_at 설정
3. 감사 이벤트 저장
4. 트랜잭션 커밋

회수된 레코드는 이력으로 유지한다. 조건부 UPDATE의 변경 행 수와 소유자 조건의 후속 조회로 최초 회수·반복 회수·미존재/무권한을 구분한다. 실제 회수와 반복 요청의 감사 사유를 구분하며, 반복 요청도 감사 커밋 후 204를 반환한다. 권한 변경 필수 감사 실패는 전체 변경을 롤백하고 503으로 반환한다.

권한 조회는 매 요청마다 DB의 활성 상태를 확인하며 캐시하지 않는다.

## 6. 초기 합성 데이터

| 주체 | 데이터·권한 |
|---|---|
| 고객 A | A-01, A-02 계좌 소유 |
| 고객 B | B-01 계좌 소유 |
| 제휴사 X | A-01의 ACCOUNT_READ 권한 |
| 제휴사 Y | 초기 위임 권한 없음 |

테스트 데이터는 고정된 권한 관계를 재현할 수 있도록 `src/test/resources/fixtures/financial-data.sql`로 구성한다. 일반 Flyway 마이그레이션에는 데이터를 넣지 않는다. 고객 A/B, 제휴사 X/Y의 sub는 각각 `customer-a/b`, `partner-x/y`다. A-01은 동일 시각의 입출금 2건, A-02는 빈 계좌, B-01은 별도 거래 1건을 갖는다.

## 7. 후속 구현 결정

- 향후 금액 입력 API의 정밀도 검증 규칙(조회 시각은 UTC ISO 8601로 구현)
- 탐지 이벤트/억제 상태의 운영 보존 기간은 별도 결정한다.
- 권한 변경·감사 트랜잭션 및 전체 API 경합은 6단계에서 구현·검증하며 결과는 해당 구현 계획과 테스트 문서에 기록한다.

## 8. 관련 문서

- [아키텍처](architecture.md)
- [보안 요구사항](security-requirements.md)
- [API 명세](api-spec.md)
