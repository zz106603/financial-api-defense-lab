# 개발 프로필로 실제 API 확인

Step7 이후 Evals 전에 실제 서명 JWT·HTTP·PostgreSQL로 동작을 확인한다. 별도 데모 서버·Compose·관리 API는 사용하지 않는다. 기본 application.yaml은 유지하고 `SPRING_PROFILES_ACTIVE=dev`로 application-dev.yaml을 추가 적용한다.

## 1. DB와 JWT 준비

JDK21, PowerShell7, Docker Desktop Linux 엔진을 준비하고 저장소 루트에서 실행한다. 기존 `.env`는 덮어쓰지 않는다.

```powershell
# .env가 없을 때만 복사하고 개발용 DB 비밀번호 등을 설정한다.
if (-not (Test-Path .env)) {
    Copy-Item .env.example .env
}
docker compose up -d --wait postgres
java scripts/DevTokens.java init .local-dev

# 현재 터미널에만 적용한다. Spring Boot는 .env의 DB 설정을 dev에서 읽는다.
$env:SPRING_PROFILES_ACTIVE = 'dev'
$env:JWT_ISSUER = 'https://issuer.local.invalid'
$env:JWT_AUDIENCE = 'financial-api-defense-lab'
$env:JWT_PUBLIC_KEY_LOCATION = 'file:./.local-dev/public.pem'
$env:AUDIT_RESOURCE_HMAC_KEY = (Get-Content .local-dev/audit-hmac.key -Raw).Trim()
.\gradlew.bat bootRun
```

DevTokens는 합성 customer-a/b·partner-x/y에 대해 RS256·30분 토큰을 발급한다. 개인키·공개키·HMAC 키·tokens.json만 Git 제외된 `.local-dev/`에 저장한다. DB 비밀번호나 프로세스 상태는 관리하지 않는다. 갱신은 `java scripts/DevTokens.java tokens .local-dev`이며 기존 키를 유지한다. HMAC 키도 재기동 시 유지해야 감사 가명 계좌가 일관된다. 개발 키를 운영 신뢰키로 등록하지 않는다.

기본 실행은 창·억제300초/주기30초다. dev YAML은 창·억제30초/주기2초로 줄인다. dev에서도 원래 정책을 쓰려면 실행 전 DETECTION_WINDOW_SECONDS=300, DETECTION_COOLDOWN_SECONDS=300, DETECTION_POLL_MILLIS=30000을 환경변수로 설정한다. 시간 변경 후 앱을 재기동한다. 집계5회·서로 다른3계좌 조건과 원자성은 동일하며 단축 확인으로 운영5분 정책 검증을 대체하지 않는다.

## 2. 기존 Fixture 적용

앱 기동으로 Flyway 마이그레이션이 완료된 뒤 다른 터미널에서 수행한다. `src/test/resources/fixtures/financial-data.sql`을 수정 없이 재사용한다. 고객A의 A-01/A-02, 고객B의 B-01 세 계좌가 있고 X는 A-01 ACCOUNT_READ만, Y는 위임이 없다. Y로 세 계좌의 무단 접근을 재현할 수 있어 데이터를 늘릴 필요가 없다.

아래 삽입은 **비어 있는 로컬 합성 개발 DB에서 한 번만** 실행한다. 기존 데이터가 있으면 재삽입하지 않는다. 자동 초기화나 TRUNCATE는 제공하지 않는다.

```powershell
('BEGIN;' + "`n" + (Get-Content src/test/resources/fixtures/financial-data.sql -Raw) + "`nCOMMIT;") |
    docker compose exec -T postgres sh -c 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
```

## 3. API 호출

```powershell
.\scripts\dev-api.ps1 Access
.\scripts\dev-api.ps1 Detection
```

Access는 고객 본인200/타인404, X 범위200/404, Y 권한 부여201→거래조회200→회수204→거부404→반복회수204를 호출하고 응답·requestId를 출력한다. Detection은 Y로 세 계좌에5회404만 호출한다. 기본 URL은8080이며 필요하면 `-BaseUrl http://127.0.0.1:8081`을 지정한다. 토큰·Authorization은 출력하지 않는다.

탐지 확인은 Detection 호출 후 약2~3초 뒤 DB를 조회하고, 즉시 다시 Detection을 호출해 다음 주기에도 알림이 추가되지 않는지 확인한다. 첫 알림의 detected_at에서30초 이상 지난 뒤 Detection을 다시 호출하고 약2~3초 뒤 재알림을 확인한다. 스크립트가 자동으로 시간을 기다리거나 DB 시각을 바꾸지 않는다. 과거 다른 모드의 알림·감사는 보존되므로 이번 requestId와 창 길이를 기준으로 구분한다.

## 4. DB 확인

```powershell
docker compose exec postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
```

psql에서 아래 SQL을 실행한다. 종료는 `\q`, 앱은 실행 터미널의 Ctrl+C, DB 정지는 `docker compose stop postgres`를 사용한다. 키·볼륨은 보존한다.

```sql
SELECT action,result,reason_code,resource_ref,request_id,occurred_at
FROM audit_event ORDER BY occurred_at DESC LIMIT 20;

SELECT rule_id,principal_id,violation_count,distinct_resource_count,
       window_start,window_end,window_end-window_start AS window_length,detected_at,
       detected_at-lag(detected_at) OVER (PARTITION BY rule_id,principal_type,principal_id ORDER BY detected_at) AS alert_gap
FROM security_alert ORDER BY detected_at DESC LIMIT 10;

SELECT *,last_alert_at+interval '30 seconds' AS dev_suppression_until
FROM detection_state;
```

| 테이블 | 정상 결과 확인 기준 |
|---|---|
| audit_event | 이번5개 requestId의 ACCOUNT_READ / DENIED / OBJECT_ACCESS_DENIED가5행이고 resource_ref가3종류. 계좌 원문 대신 HMAC 가명 |
| security_alert | R-01, violation_count≥5, distinct_resource_count≥3, dev 창 길이30초. 억제 중 행 수 유지, 만료 후 조건 재충족 시 새 행과 alert_gap≥30초 |
| detection_state | 같은 주체·R-01에1행. 억제 중 last_alert_at 유지, 재알림 커밋 시 새 detected_at과 같은 값으로 갱신 |

표의 억제 종료 계산은 dev 기본30초 기준이며 시간 설정을 바꾸면 그 간격도 맞춰 조회한다. 기존 Access의 거부도 집계 창 안이면 알림 횟수가5보다 클 수 있다. POST 커밋 후 응답 유실·강제 종료로 회수가 안 됐다면 해당 grantId를 DB에서 확인하고 customer-a JWT로 기존 DELETE API를 호출한다. 호출 스크립트는 프로세스 복구·자동 데이터 정리를 담당하지 않는다.

2026-11-20 검증: clean build와 전체189개 테스트 통과(실패·오류·건너뜀0). dev JAR·기존 Compose PostgreSQL에서 Access10개 실제 요청의 응답과 요청 ID별 감사 action/result 일치를 확인했다. 변조 JWT401·잘못된 UUID400과 대응 오류 감사도 확인했다. Detection의 각5개 요청은 감사5행/3가명계좌로 확인됐고, 추가 요청 후 억제 중 알림 수·last_alert_at이 유지됐다. 두 R-01 알림은 각각5회/3계좌·창30초, 간격30.006048초였으며 마지막 억제 상태와 알림 detected_at이 일치했다. 이 실행의 재알림은 만료 경계에 직전 추가 묶음으로 생성돼 마지막 새 묶음의 집계 창 포함 수는0이었다. 마지막 묶음의 감사 검증과 재알림 조건·간격 증거는 구분한다.

독립 읽기 전용 리뷰에서 Critical/Important 지적은 없었고, 미검증 표시를 실제 결과로 갱신했다. 이는 개발30초 확인이며 실제5분 대기 검증·원격CI·보안 Evals와 구분한다. 기본5분 경계·동시성은 PostgreSQL 자동 회귀에서 검증했다. 사용하지 않는 이전 데모 폴더·Java 프로세스·전용 PostgreSQL 컨테이너와 볼륨은 제거했다. 현재 개발 키와 기존 Compose 개발 DB는 유지한다.
