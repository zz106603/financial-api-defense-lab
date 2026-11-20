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

### 4.1 4단계 구현

- `common.security.SecurityConfiguration`이 Servlet 웹 환경에서 보안 필터 체인과 Decoder를 구성한다. 비웹 DB 테스트는 웹 보안 구성을 생성하지 않는다.
- 별도 인증 서버를 추가하지 않는 현재 구성에서는 X.509 PEM 형식 RSA 공개키 파일을 사용한다. `security.jwt.issuer`, `audience`, `public-key-location`을 외부에서 제공한다. 웹 기동 시 설정 누락·읽을 수 없는 공개키·잘못된 PEM·2048비트 미만 RSA 키는 실패 처리한다. 제품에 기본 신뢰 키나 개인키를 포함하지 않는다.
- `NimbusJwtDecoder`가 RS256만 허용하고 서명을 검증한다. `iss`는 설정값과 정확히 일치하고 `aud`에 설정한 API audience가 포함되어야 한다. `exp`는 필수이며 만료를 허용하지 않는다. `nbf`는 존재하면 활성화 시각을 검사한다. 시각 허용 오차는 0초이며 서버 시각 동기화가 필요하다.
- `sub`는 비어 있지 않은 문자열이고 `principal_type`은 대소문자가 일치하는 `CUSTOMER` 또는 `PARTNER`만 허용한다. 타입이 잘못되거나 누락된 클레임과 DB에 없는 주체는 인증 실패다.
- `JwtPrincipalConverter`가 유형에 해당하는 기존 Mapper의 `auth_subject`로 매 요청 주체를 찾는다. 두 테이블에 같은 sub가 있어도 유형을 혼용하지 않는다. 인증 결과의 `AuthenticatedPrincipal(UUID id, PrincipalType type)`에는 DB 식별자·유형만 담는다. 이후 API는 `@AuthenticationPrincipal`로 이 값을 받아야 한다.
- 역할은 서버가 유형으로 결정한 `ROLE_CUSTOMER` 또는 `ROLE_PARTNER` 하나다. JWT의 scope·roles·추가 고객/제휴사 ID는 권한으로 변환하지 않는다. 인증 객체의 credentials에 JWT 원문을 보관하지 않는다. 계좌별 인가는 이후 업무 계층 및 기존 SQL에서 별도로 수행한다.
- 일반 요청은 인증을 요구한다. 컨테이너 ERROR 디스패치는 오류 응답 전달만 허용하며 직접 `/error` 요청은 인증 대상이다. HTTP Basic·폼 로그인·로그아웃과 세션 저장·요청 캐시를 사용하지 않는다. 토큰은 Authorization Bearer 헤더로만 받는다. CSRF 검사는 쿠키·세션 인증을 사용하지 않는 Bearer 전용 API 구성에서 비활성화한다.
- Method Security를 활성화해 주체 역할을 후속 API의 기능별 접근 제한에 사용할 수 있게 한다. 역할 확인 자체는 계좌 소유권/위임 확인을 대체하지 않는다.
- 인증 실패/역할 거부/주체 DB 장애는 세부 원인을 외부에 노출하지 않는 JSON 401/403/503으로 반환한다. DB 장애 로그는 고정 메시지만 기록하고 토큰·sub·SQL·예외 상세를 남기지 않는다. 오류 코드와 추적 ID 형식은 [API 명세](api-spec.md#보안-오류-응답)를 따른다.
- 테스트 키는 JVM 실행 중 생성한다. 개인키는 메모리에만 있고 임시 파일에는 공개키만 저장한다. 테스트 전용 Controller는 테스트 설정으로만 등록하며 배포 JAR에는 포함하지 않는다. 서명 검증 테스트는 실제 Bearer 헤더를 사용하고 JwtDecoder 모킹이나 `jwt()` 모의 인증을 사용하지 않는다.

현재는 고정 공개키 검증이므로 키 교체 시 설정 변경·애플리케이션 재기동이 필요하다. JWKS 자동 키 순환, 토큰 발급 및 토큰 즉시 폐기는 구현하지 않는다.

## 5. 인가 및 금융정보 조회

Authorization Service가 요청 주체·대상 계좌·조회 행위에 대한 정책을 관리한다.

실제 조회는 MyBatis SQL에 인가 조건을 포함한다.

- CUSTOMER: 계좌 소유권 확인
- PARTNER: 계좌·제휴사·필요 권한·미회수 상태 확인
- 그 외 또는 검증 불가: 거부

인가 확인과 데이터 조회 사이의 권한 변경 위험을 줄이기 위해 금융정보 조회 SQL에서 권한 조건을 함께 평가한다.

조회 경로의 불필요한 사전 권한 조회와 정책 중복 구현은 피한다. 데이터 조회 결과가 없을 때 외부에는 객체 존재 여부를 노출하지 않는다.

### 5.1 5단계 조회 구현

- `account.AccountController`는 JWT에서 식별한 `AuthenticatedPrincipal`과 검증한 UUID·페이지를 `AccountQueryService`에 전달한다. 일반 계좌/거래 영속성 모델을 직접 응답하지 않고 명세 필드만 가진 DTO를 반환한다.
- `authorization.FinancialReadAuthorizationService`는 주체 유형·UUID를 검사한 뒤 `AccountMapper.readAccount/readTransactionPage`를 호출한다. 별도 사전 인가 SQL은 없으며 실제 데이터 SQL에 소유권 또는 계좌·제휴사·개별 미회수 권한 조건을 적용한다.
- 각 조회는 별도 `READ COMMITTED` 읽기 전용 트랜잭션으로 실행해 호출 측의 오래된 스냅샷을 물려받지 않는다. MyBatis STATEMENT 캐시 설정을 유지한다.
- 단일 SQL의 target CTE와 LATERAL 조인으로 최소 한 행의 내부 상태를 반환한다. 미존재/무권한의 금융 필드는 null이며, 거래가 없는 허용된 계좌/페이지는 authorizedAccountId를 유지한다. 무권한과 미존재의 존재 여부는 감사 사유에만 사용한다.
- 페이지 기본값 0/20·size 최대 100, long offset, 거래 시각/UUID 내림차순과 size+1 방식의 hasNext를 적용한다.
- `AccountQueryService`는 조회 후 `audit.ReadAuditService`의 별도 Bean 프록시로 동기 감사하고, 커밋이 끝난 뒤에만 성공 또는 404를 반환한다. 감사 실패면 고정 운영 오류를 기록하고 503으로 차단한다.
- `common.web.RequestIds`는 조회 성공·오류·감사 및 보안 오류에서 같은 서버 UUID를 사용한다. `ApiExceptionHandler`는 고정된 조회 오류를 반환하고 SQL·예외 상세를 응답/운영 오류 메시지에 넣지 않는다.

5단계에서는 금융정보 반환의 선행 조건인 조회용 최소 감사를 포함했다. 권한 변경과 감사의 동일 트랜잭션은 아래 6단계에서 구현했으며, 인증/입력 오류 감사와 탐지 집계는 아래7단계에서 구현했다.

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

동일한 활성 권한의 중복 생성은 기존 PostgreSQL 부분 유니크 인덱스 uq_active_access_grant로 방지한다. 별도 SELECT FOR UPDATE·advisory lock·애플리케이션 lock은 추가하지 않는다. 동시 부여에서 해당 인덱스 충돌만 409로 변환하고 다른 무결성 오류와 구분한다. 충돌로 실패한 트랜잭션을 롤백한 뒤 필요한 거부 감사를 독립 트랜잭션으로 저장한다.

회수는 계좌·grantId·소유자·revoked_at IS NULL을 제한한 조건부 UPDATE를 사용한다. UPDATE 자체의 행 잠금을 활용하며, 0행이면 소유자 조건의 새 조회로 이미 회수된 권한과 미존재/무권한을 구분한다. 이미 회수된 소유 권한은 revoked_at을 보존하고 반복 회수 감사 후 204를 반환한다. 실제 회수 감사와 반복 요청 감사는 사유를 구분한다.

인증된 PARTNER의 권한 변경 역할 거부도 접근 거부 감사 대상이다. Controller/보안 경계에서 서비스 진입 전에 거부되더라도 독립 감사를 완료한 뒤 403을 반환하며, 필수 감사 실패는 503이다. 인증 실패·입력 오류는 아래7단계의 별도 정책을 적용한다.

권한 변경 필수 감사 실패는 전체 변경을 롤백하고 503 SERVICE_UNAVAILABLE로 반환한다. 반복 회수의 필수 감사 실패도 503이며 성공 응답은 감사 커밋 이후에만 반환한다.

계좌 행 전체 잠금은 서로 다른 제휴사·권한 변경까지 직렬화하므로 사용하지 않는다. 조회에도 잠금을 추가하지 않는다. 회수된 grantId와 별도로 새 권한을 부여할 수 있으며, 이전 grantId의 회수는 새 권한을 취소하지 않는다.

### 6.1 6단계 권한 변경 구현

- AccessGrantController는 표준 UUID·요청 DTO를 검증하고 서버 생성 requestId와 검증된 주체를 AccessGrantService에 전달한다. 역할 검사와 감사가 분리되지 않도록 조정 서비스에서 CUSTOMER 여부를 확인한다. 인증된 PARTNER의 유효한 형식 요청은 독립 감사 후 403이며, 인증/입력 오류 감사는 아래7단계의 별도 경계에서 수행한다.
- AccessGrantTransactionService의 별도 Bean 프록시는 REQUIRES_NEW·READ_COMMITTED 쓰기 트랜잭션을 연다. 소유자 UUID·대상 제휴사를 확인하고 소유권 조건을 포함한 변경 SQL을 호출한다. GrantAuditService의 MANDATORY로 변경과 성공 감사를 원자적으로 저장한다. 프록시 커밋까지 완료한 뒤에만 201/204를 반환한다.
- insertActive는 ON CONFLICT (account_id, partner_id, permission) WHERE revoked_at IS NULL DO NOTHING으로 기존 부분 UNIQUE에 대응하는 충돌만 0행으로 받는다. 조정 서비스는 업무 거부로 롤백을 끝낸 뒤 GrantDeniedAuditService의 REQUIRES_NEW로 거부 감사하고 409를 반환한다. 다른 제약·DB·필수 감사·커밋 실패는 내부 상세 없는 503이다.
- 회수 UPDATE 0행은 findForOwner로 새 스냅샷에서 재확인한다. 이미 회수된 소유 권한만 204이며 활성 상태인데 변경되지 않았다면 장애로 처리한다. 최초 회수는 GRANT_REVOKED, 반복은 GRANT_ALREADY_REVOKED로 구분하고 revoked_at을 보존한다.
- 변경 트랜잭션이나 독립 거부 감사가 호출 측 업무 트랜잭션과 중첩되면 추가 연결이 필요하다. 풀 크기는 실제 동시 요청 수와 중첩 호출을 고려해야 한다. 권한 변경 응답은 독립 커밋이므로 호출 측의 나중 롤백으로 변경을 취소하지 않는다.
- 테스트 전용 MyBatis interceptor가 실제 SQL 실행 뒤 반환만 일시 정지한다. pg_blocking_pids로 경쟁 연결의 실제 DB 대기를 확인하며, DB 트리거로 INSERT 및 지연 커밋 실패를 주입한다. 테스트 장치는 배포 코드에 포함하지 않는다.

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

조회 허용·거부는 `ReadAuditService.record`의 `REQUIRES_NEW`로 독립 커밋한다. 일반 HTTP 조회 경로에서는 읽기 트랜잭션을 종료한 뒤 감사 트랜잭션을 시작한다. 호출 측에 별도 업무 트랜잭션이 있으면 그 연결을 유지한 채 추가 연결을 사용하므로 중첩 호출의 동시성에 맞는 풀 크기가 필요하다. [Spring 트랜잭션 전파 문서](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html)의 연결 수 주의사항을 따른다.

감사에는 검증된 주체 UUID·유형, 조회 행위, 가명 계좌 참조, 결과·사유·요청 ID만 전달한다. 계좌 가명화 키는 `AUDIT_RESOURCE_HMAC_KEY` 외부 설정이며 세부 형식과 키 변경 영향은 [데이터 모델](data-model.md#43-조회-감사와-가명-참조)을 따른다.

감사 저장소 장애로 기록을 남길 수 없으면 운영 오류를 기록하고 접근은 허용하지 않는다. 저장소 장애 시 감사 기록의 완전한 보존은 보장하지 않는다.

### 6.2 인증·입력 오류 감사

- RequestFailureAuditor는 MVC와 동일한 RequestPath/PathPattern의 세그먼트별 디코딩으로 네 금융API의 경로·메서드만 고정 action으로 분류한다. URI 전체를 임의로 디코딩하지 않는다. 인증 실패는 SecurityErrorHandler.commence에서, 입력 오류는 ApiExceptionHandler의 형식 오류 및 업무400 경계에서 연결한다. 동일 요청은 한 번만 시도한다.
- RequestFailureAuditService.record는 REQUIRES_NEW로 저장한다. 프록시 커밋 실패까지 호출 경계에서 포착해 원래401/400을 유지하고 고정 운영 오류를 기록한다. 이는 새 인증/입력 오류 감사에만 적용하며 기존 필수 감사503과 구분한다.
- 인증 실패는 주체를 항상ANONYMOUS/null로 사용한다. 입력 오류는 SecurityContext의 검증된 AuthenticatedPrincipal만 사용한다. 표준 UUID가 아닌 대상은resource_ref=null이다. DB의 객체 존재 여부를 추가 조회하지 않는다.
- 토큰·클레임·HTTP본문/쿼리/경로·오류원문은 감사 서비스에 넘기지 않는다. 서버 요청ID는 감사·응답 헤더·오류 본문에 동일하게 사용하며 감사 실패를 재귀 감사하지 않는다.

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

### 7.1 집계·중복 억제 구현

- DetectionService.detect는 별도 REQUIRES_NEW/READ_COMMITTED 트랜잭션에서 DetectionMapper.currentTime의 DB statement_timestamp를 한 번 읽어T로 고정한다. candidates는 occurred_at > T-5분 AND occurred_at <= T의 커밋된 이벤트를 집계한다. 창 시작은 제외·끝은 포함하고 미래 이벤트는 제외한다.
- result=DENIED, reason_code=OBJECT_ACCESS_DENIED, CUSTOMER/PARTNER 유형·주체UUID·가명 대상이 있는 ACCOUNT_READ/TRANSACTION_READ/GRANT_CREATE/GRANT_REVOKE만 집계한다. 유형과UUID로 그룹화하고 COUNT>=5·서로 다른resource_ref>=3을 모두 만족해야 후보가 된다.
- detection_state의 PK(rule_id,principal_type,principal_id)와 조건부UPSERT로 DB 현재시각과 집계창T 모두 이전 last_alert_at에서5분 이상 경과한 새 창만 원자적으로 선점한다. 만료 전 후보가 잠금 대기 후 재알림되는 것을 막는다. last_alert_at은 선점 시 DB clock_timestamp이며 성공 알림의 detected_at과 같다. 실패한 트랜잭션의 상태는 남지 않는다.
- suppression 상태 변경과 SecurityAlert INSERT는 한 트랜잭션이다. INSERT·커밋 실패 시 모두 롤백해 다음 실행이 다시 시도한다. 경합한 실행도 DB 상태를 기준으로 한 번만 생성한다. 다른 주체·규칙의 억제 상태는 독립적이다.
- DetectionScheduler.run은 기본적으로 기동30초 후부터30초 fixedRate로 별도 서비스를 호출한다. 실패 시 원문 없는 운영 오류를 남기고 다음 호출을 유지하며 금융API결과를 바꾸지 않는다. 자동 차단은 하지 않는다.
- DetectionTiming의 기본값은 창300초·억제300초·주기30000ms다. detection.window-seconds/cooldown-seconds/poll-millis 설정을 읽고 양수를 검증한다. 서비스의 창 시작·Mapper의 바인딩된 SQL 간격·스케줄러가 같은 설정을 사용한다. application-dev.yaml은30/30/2000으로 덮어쓰며 별도 demo 전용 분기는 없다. 기본 application.yaml은 유지하고 개발 프로필의 단축 결과를 운영5분 정책 검증과 구분한다.
- detection.scheduling.enabled는 기본true다. 테스트 프로필에서만 자동 실행을false로 두고 실제 서비스를 직접 호출해 DB·API 연결과 경합을 검증한다. 운영에서 비활성화하면 탐지가 실행되지 않는다.
- 재기동 후에도 DB의 억제 상태를 유지한다. 발생 시각 기준의 최근5분만 보므로 늦은 커밋이 창을 벗어나거나 장기 중단된 과거 이벤트를 소급 탐지하는 것은 보장하지 않는다. 전체 후보의 상태·알림은 배치 단위로 커밋·롤백한다.

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

보안 Evals는 [실행 안내](security-evals.md)에 따라 기존 API 보안 테스트를 재사용한다. Python 표준 라이브러리 도구가 허용된 소스·Gradle 파일만 임시 복사해 정상 기준선 및 단일 결함4개를 순차 실행한다. JUnit XML의 정확한 assertion으로 판정하며 컴파일·환경 오류·timeout·미탐지를 구분한다. 정상 CI와 별도의 수동 Security Evals 워크플로에서 보고서·로그·XML을 보존한다. Agent 자동 수정·배포는 구현하지 않는다.

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

- 로컬 합성 주체의 외부 JWT 발급·호출 도구는 [개발 환경 안내](local-development.md)로 제공한다. 운영 발급자 연동·키 교체 방식은 별도 범위다.
- [SQL·트랜잭션 경계의 실제 동시성 검증](#권한-회수와-조회의-동시성)
- 향후 새 API 추가 시 오류 감사 대상과 고정 action 매핑 확장
- 탐지 장기 중단·늦은 커밋 소급 처리 및 운영 부하에 따른 집계 최적화
- [보안 Evals·검증 하네스 실행 절차](security-evals.md). 저장소 브랜치 보호·독립 리뷰 승인 강제와 Agent 식별·수정 효율 비교는 별도 범위다.

## 11. 관련 문서

- [보안 요구사항](security-requirements.md)
- [데이터 모델](data-model.md)
- [API 명세](api-spec.md)
