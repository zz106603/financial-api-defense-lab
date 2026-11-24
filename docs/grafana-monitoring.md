# Grafana 로컬 보안 모니터링

기존 PostgreSQL의 audit_event·security_alert·detection_state를 읽는다. 기존 앱·JWT·감사·R-01을 그대로 사용하며 추가 서버는 Grafana13.3.1 컨테이너1개뿐이다. Prometheus·Loki·별도 프런트엔드·관리 API·새 알림 엔진은 추가하지 않는다.

## 준비와 실행

Docker Desktop Linux 엔진·PowerShell7을 사용한다. 저장소 루트에서 [dev 환경](local-development.md)의 DB·JWT·앱을 먼저 준비한다. 기존 Fixture가 있으면 다시 삽입하지 않는다. Flyway가 세 테이블을 만든 뒤 아래 계정을 적용한다.

```powershell
docker compose up -d --wait postgres

# 서로 다른24자 이상 비밀번호. 현재 터미널에만 설정하며 화면에 표시하지 않는다.
$env:GRAFANA_DB_PASSWORD = Read-Host 'Grafana DB password (24+ chars)' -MaskInput
$env:GRAFANA_ADMIN_PASSWORD = Read-Host 'Grafana admin password (24+ chars)' -MaskInput

# 기존 DB에도 적용 가능. psql이 환경변수에서 비밀번호를 읽는다.
Get-Content monitoring/grafana-reader.sql -Raw |
    docker compose exec -T -e GRAFANA_DB_PASSWORD postgres sh -c 'psql -X -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
# 비정상 종료면 Grafana를 시작하지 않고 오류를 확인한다.

docker compose --profile monitoring up -d --wait grafana
```

접속: **http://127.0.0.1:3000/d/financial-security**. 기본 관리자는 admin이며 위 관리자 비밀번호로 로그인한다. 필요하면 GRAFANA_PORT·GRAFANA_ADMIN_USER를 지정한다. 회원가입·익명 접근은 꺼져 있다. PostgreSQL은 호스트 포트가 아닌 Compose 내부 postgres:5432로 연결한다. 두 비밀번호가 없거나24자 미만이면 Grafana만 기동을 거부하며 기존 postgres 명령은 사용할 수 있다.

비밀번호는 현재 터미널을 닫으면 사라지므로 안전하게 보관하거나 Git 제외된 .env에 저장한다. 단, psql의 `exec -e GRAFANA_DB_PASSWORD`에는 현재 터미널 환경변수도 필요하다. 위와 같이 입력하면 같은 값을 DB와 Grafana에 전달할 수 있다. 비밀번호를 SQL에 직접 쓰거나 psql SQL echo·상세 statement 로깅을 활성화하지 않는다.

이번 검증에서 생성한 비밀은 Git 제외된 `.local-dev/monitoring.env`에 보존했다. 기존 .env는 변경하지 않았다. 이 파일을 재사용하는 경우:

```powershell
Get-Content .local-dev/monitoring.env | ForEach-Object {
    $pair = $_ -split '=', 2
    [Environment]::SetEnvironmentVariable($pair[0], $pair[1], 'Process')
}
docker compose --env-file .env --env-file .local-dev/monitoring.env --profile monitoring up -d --wait grafana
```

초기 관리자 비밀번호는 Grafana 볼륨 최초 생성에만 적용된다. 기존 관리자 암호 변경은 Grafana의 비밀번호 변경 기능을 사용하고 볼륨 삭제로 해결하지 않는다. DB 비밀번호를 바꾸면 같은 값으로 계정 SQL을 재적용한 뒤 Grafana를 재생성해 연결을 갱신한다.

## 읽기 전용 권한

전용 grafana_reader는3개 테이블 SELECT·public 스키마 USAGE·해당 DB CONNECT만 직접 부여받는다. 금융정보 테이블 조회·감사/탐지 데이터 쓰기·영구 DDL·역할 승격 권한은 없다. READ ONLY 기본값을 꺼도 이러한 권한은 생기지 않는다. PUBLIC을 통해 금융정보나 쓰기 권한이 노출된 경우 SQL은 전체 트랜잭션을 중단하며 다른 사용자의 PUBLIC 권한을 임의로 바꾸지 않는다. 동명 무관한 계정·다른 역할의 멤버인 계정도 변경하지 않는다.

연결 제한3, statement_timeout5초, lock_timeout1초, idle transaction5초를 적용한다. Grafana 풀은 최대2개다. read-only 설정 자체를 보안 경계로 믿지 않고 GRANT/REVOKE와 실제 거부 테스트로 검증한다. PostgreSQL 기본 카탈로그·PUBLIC의 임시 객체/함수 권한까지 제거하는 SQL 샌드박스는 아니다. 향후 PUBLIC 실행 가능한 쓰기 SECURITY DEFINER 함수나 다른 스키마를 추가하면 권한 범위를 다시 검토해야 한다.

DB TLS disable은 로컬 Compose 네트워크 한정이다. 외부·운영 배포용 설정으로 사용하지 않는다. 관리자·데이터소스 설정을 볼 수 있는 사용자에게만 Grafana 로그인을 허용한다. datasource는 editable=false이고 dashboard는 provisioning 파일로 관리한다.

## 화면과 정책 선택

| 패널 | 해석 |
|---|---|
| Access allowed/denied·trend·denial reasons | 시간 선택기의 허용·거부·사유별 추이. 조회 범위는 최대 최근24시간 |
| Unauthorized access / fixed last5minutes | DB 시각 기준 (T-5분,T]. 대시보드 시간 선택기·R-01 정책 변수와 독립 |
| R-01 policy candidates / inferred suppression | 앱과 같은 창·필터로 주체별 위반 횟수·계좌 수·조건충족·억제 종료·남은 초 |
| Persisted alerts | 저장된 알림의 실제 창 길이·시각·이전 알림과의 간격. 첫 알림은 선택 범위 내 이전 행이 없으면 간격 없음 |
| Recent audit | 최근200행의 결과·사유·HMAC 가명계좌·requestId. 계좌/거래 원문은 조회하지 않음 |

상단 **window seconds / cooldown seconds**를 실제 앱과 일치시킨다. dev 기본은30/30, 일반 기본은300/300이다. 선택은 앱 설정을 바꾸지 않으며 현재 앱 설정이 DB에 저장되지 않아 자동 검증할 수 없다. matches_selected_window도 알림의 창 길이만 비교하며 억제 기간까지 보장하지 않는다. 다른 시간 설정을 사용하면 dashboard JSON 변수 선택지도 함께 관리해야 한다.

R-01 후보는 기존 Mapper와 동일한 DENIED/OBJECT_ACCESS_DENIED, CUSTOMER/PARTNER·주체UUID·가명대상이 있는 네 행위만 포함한다. 단순401/400 감사는 별도 거부 사유 통계에 나타나며 후보에는 포함되지 않는다.

- SUPPRESSED: 마지막 알림에서 선택한 cooldown이 지나지 않은 상태. 실제로 건너뛴 실행 횟수는 DB에 없으므로 표시하지 않는다.
- ELIGIBLE_NOT_A_GUARANTEE: 억제 만료 후 현재 조건충족 상태. 스케줄러 실행·성공을 보장하는 상태가 아니다.
- NO_PREVIOUS_ALERT / CONDITIONS_NOT_MET / STALE_WINDOW: 이전 알림 없음 / 현재 조건 미충족 / 과거 또는 동일 창 상태다.

5초 자동 새로고침은 각 패널의 별도 SQL이다. 전체 화면을 하나의 트랜잭션 스냅샷으로 보장하지 않는다. DB의 알림·억제 상태 커밋 원자성은 기존 앱이 보장한다. LIMIT·24시간 조건만으로 스캔 비용이 제한되는 것은 아니며 현재 일반 감사 통계에는 선두시간 인덱스가 없다. 로컬 합성 데이터 범위로 사용하고 데이터 증가 시 실행계획·시간 인덱스를 별도 검토한다.

## 실제 시연 순서

1. dev 앱이30초 창·억제/2초 주기로 실행 중인지 확인한다. 기존 [실행 안내](local-development.md)를 따른다.
2. `./scripts/dev-api.ps1 Access`: 고객·제휴사 허용/거부, 권한 부여·회수·반복 DELETE를 확인한다. 이번 요청 ID로 감사를 대조한다.
3. `./scripts/dev-api.ps1 Detection`: Y가 세 계좌에5회404. 후보와 고정5분 표에 집계되고 약2초 후 R-01 알림이 생긴다.
4. 억제 중 다시 Detection: 감사는 증가해도 알림·last_alert_at은 유지된다. remaining_seconds가 감소한다.
5. 마지막 알림에서30초 경과 후 다시 Detection: 최신 조건이 충족되면 재알림한다. 그 사이 추가 요청이 남아 있어 먼저 재알림될 수도 있으므로 alert의 window_end와 감사 시각을 대조한다.

조회·모니터링이 DB 시각이나 감사/억제 상태를 수정하지 않는다. Grafana 알림 규칙·자동 차단을 추가하지 않으며 R-01 생성은 기존 앱만 수행한다.

## 검증 기록과 종료

2026-11-24: Grafana13.3.1 health200, datasource Database Connection OK, dashboard8패널 provisioning과7개 SQL 패널의 실제 Grafana /api/ds/query 성공을 확인했다. 익명 dashboard API는401이다. 별도 브라우저의 시각적 렌더링 검증은 수행하지 않았다.

실제 JWT Access10개는 감사 허용6/거부4와 일치했다. Detection 각 묶음은5행/3가명계좌로 확인됐고, 억제 중 감사10행·알림 수/last_alert_at 유지·남은 시간 감소, 알림 간격30.003117초의 재알림과 마지막 상태/알림 시각 일치를 Grafana 조회로 확인했다. 재알림은 직전 추가 묶음으로 먼저 생성돼 마지막 묶음의 해당 창 포함 수는0이었다. 마지막 묶음의 감사 증거와 알림 집계 증거를 구분한다.

GrafanaMonitoringIntegrationTest5개와 기존189개, 총194개 테스트·build --rerun-tasks 통과(실패/오류/skip0). 테스트는 실제 PostgreSQL에서 비밀번호 인증, READ ONLY 해제 후에도 금융 SELECT/쓰기/영구 DDL/역할 승격 거부, PUBLIC 과잉권한 중단, 모든 패널 SQL,5분/정책창 경계·억제 만료를 확인했다. 독립 읽기 전용 리뷰에 수정 필요 지적은 없었고 전체스캔 가능성을 운영 한계로 반영했다. 첫 Windows 앱 기동 소켓 오류는 실행 한정 JAVA_TOOL_OPTIONS IPv4 설정 후 정상 기동으로 확인했으며 제품 설정은 변경하지 않았다.

```powershell
docker compose --profile monitoring stop grafana
```

검증용 dev Java 앱은 종료했고 Grafana·PostgreSQL은 healthy 상태로 유지했다. 새로운 API 요청을 보내려면 자신의 터미널에서 dev 앱을 시작한다. Grafana와 DB 볼륨·키는 보존한다. 개발 DB도 멈추려면 `docker compose stop postgres`를 별도로 실행한다. `down -v`로 기존 데이터를 삭제하지 않는다.
