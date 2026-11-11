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

총 7개 엔티티를 사용한다.

## 3. 주요 테이블

컬럼 표의 `—`는 기존 표에 해당 정보가 명시되지 않았음을 뜻한다. 구체적인 SQL 타입·제약의 후속 결정은 [데이터 무결성 및 인덱스](#4-데이터-무결성-및-인덱스)와 [후속 구현 결정](#7-후속-구현-결정)을 따른다.

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

`resource_ref`는 반복 접근 대상 식별에 사용할 수 있어야 하며 가명화 방법은 구현 시 결정한다.

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

동일 주체·규칙에 대한 중복 알림을 억제하고, 구체적인 중복 방지 방식은 구현 시 결정한다.

## 4. 데이터 무결성 및 인덱스

- 모든 참조 관계에 외래키 적용
- 필수 컬럼에 NOT NULL 적용
- permission 등 제한된 값에 CHECK 제약 적용
- 활성 AccessGrant의 부분 유니크 인덱스 적용
- 거래내역의 계좌별 조회 및 정렬에 필요한 인덱스 구성
- 감사 이벤트의 주체·발생 시각·판단 사유 기반 탐지 조회 지원

구체적인 SQL 타입, 길이 및 인덱스 구성은 Flyway 작성 시 확정하고 통합 테스트로 검증한다.

## 5. 권한 데이터 처리

### 부여

1. 계좌 소유권 및 대상 제휴사 확인
2. AccessGrant 신규 생성
3. 감사 이벤트 저장
4. 트랜잭션 커밋

동시 부여 요청에서 중복 활성 권한을 생성하지 않는다.

### 회수

1. 계좌 소유권 및 AccessGrant 관계 확인
2. 활성 레코드의 revoked_at 설정
3. 감사 이벤트 저장
4. 트랜잭션 커밋

회수된 레코드는 이력으로 유지한다.

권한 조회는 매 요청마다 DB의 활성 상태를 확인하며 캐시하지 않는다.

## 6. 초기 합성 데이터

| 주체 | 데이터·권한 |
|---|---|
| 고객 A | A-01, A-02 계좌 소유 |
| 고객 B | B-01 계좌 소유 |
| 제휴사 X | A-01의 ACCOUNT_READ 권한 |
| 제휴사 Y | 초기 위임 권한 없음 |

테스트 데이터는 고정된 권한 관계를 재현할 수 있도록 구성한다.

## 7. 후속 구현 결정

- [금액·시각 컬럼의 구체적인 SQL 타입](#4-데이터-무결성-및-인덱스)
- [감사 이벤트 가명화 방식](#36-audit_event)
- [탐지 이벤트 중복 방지 제약](#37-security_alert)
- [권한 회수 경합 시 SQL 업데이트 조건](#회수)
- [인덱스 구성 및 초기 데이터 삽입 방식](#4-데이터-무결성-및-인덱스)

## 8. 관련 문서

- [아키텍처](architecture.md)
- [보안 요구사항](security-requirements.md)
- [API 명세](api-spec.md)
