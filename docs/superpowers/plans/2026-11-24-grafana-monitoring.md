# Grafana 로컬 보안 모니터링 계획

- High: DB 최소 권한과 감사·탐지 조회 SQL. 기존 제품 코드·마이그레이션·정책 변경 없음. 현재 브랜치에서 순차 구현, 커밋·push 없음.
- 기존 Compose의 monitoring 프로필로 Grafana13.3.1 서비스1개만 추가한다. localhost3000, 내장 PostgreSQL 데이터소스, 기존 postgres:5432 연결. 익명 접근·회원가입·별도 알림엔진을 끄고 provisioning을 읽기 전용 마운트한다. 새 환경변수 누락은 Grafana 시작 시에만 차단해 기존 postgres 실행에 영향 주지 않는다.
- monitoring/grafana-reader.sql: 기존 DB에도 수동 적용하는 psql 스크립트. 전용 LOGIN의 CONNECT·public USAGE·audit_event/security_alert/detection_state SELECT만 부여한다. 일반 금융 테이블·쓰기·영구 DDL·역할 승격을 거부하고 연결/statement/lock 제한을 둔다. 비밀번호는 환경변수에서 읽고 SQL에 안전하게 인용한다. 동명 무관한 계정은 변경하지 않는다.
- provisioning/datasources·dashboards YAML과 dashboard JSON을 monitoring 아래 둔다. 허용/거부 통계·추이, 고정 최근5분 무단 접근, 정책 창 후보, R-01 이력·알림 간격, 억제 추정 상태·남은 시간, 최근 감사. 쿼리 결과는 제한하고 계좌/거래 원문은 읽지 않는다.
- 정책 변수 window_seconds/cooldown_seconds는30/300에서 선택한다. 실제 앱 설정을 DB만으로 자동 검증할 수 없으므로 명시적으로 맞춘다. SQL값은 sqlstring 후 정수 cast로 사용한다. 억제된 실행 횟수는 DB에 없으므로 표시하지 않는다. R-01 조건은 기존 Mapper와 같은 action·사유·주체·대상 필터와 (T-window,T] 경계를 사용한다.

## 구현·검증 순서

1. PostgreSQL 통합 테스트 RED: 실제 psql 계정 설정, 읽기 허용·금융정보/쓰기/DDL/역할 승격 거부(READ ONLY를 꺼도 거부), 대시보드 SQL·경계·후보/억제 상태.
2. 최소 SQL·Compose/provisioning·대시보드 구현과 관련 테스트 GREEN. 기존 Fixture·JWT 발급·dev-api 재사용.
3. 로컬 실제 앱·Grafana 기동. datasource health와 provisioning·패널 쿼리 응답을 확인한다. 실제 Access·Detection 호출과 요청별 감사, 억제 중 감사증가/알림·상태유지, 실시간30초 후 재알림을 Grafana 조회·DB로 대조한다.
4. 전체 보안 회귀·빌드·Compose config·비밀 제외·diff 검사. 독립 리뷰 후 타당한 지적 수정·관련 재검증.
5. docs/grafana-monitoring.md 및 관련 안내에 준비·적용·기동·관찰·종료, 실제 결과와 미검증 범위를 기록한다. 기존 키·데이터·볼륨을 삭제하지 않는다.

## 실행 기록

- feat/grafana-monitoring 생성. Grafana 공식 릴리스/다운로드에서13.3.1 확인 후 고정했다. 제품 src/main·마이그레이션은 변경하지 않았다.
- 관련 테스트 미구현 상태에서 파일/계정 부재 RED를 확인했다. 구현 후 초기4개 PASS, PUBLIC 과잉권한 중단을 추가한5개 및 전체194개 build --rerun-tasks PASS(실패/오류/skip0). 5분 통계와30초 창의 실제 차이·정확한30초 경계를 더 명확히 하는 테스트를 보강한 최종 전체194개·build도 통과했다.
- 기존 .env를 수정하지 않고 .local-dev/monitoring.env에 두 랜덤 비밀을 보존했다. 실제 DB에 읽기 계정을 적용하고 datasource health·8패널 provisioning·7개SQL 패널의 Grafana query API·익명401을 확인했다. 비밀누락/짧은값의 일회성 컨테이너는 각각 exit2/1로 기동 거부했고 자동 제거됐다.
- 실제 Access10요청 감사 허용6/거부4, Detection 묶음별5행/3가명계좌, 억제 중 감사10행·알림/last_alert_at 유지,30.003117초 재알림 및 상태 일치를 확인했다. 새 마지막 묶음 전에 직전 추가 묶음으로 재알림되어 마지막 묶음의 해당 창 포함 수는0이다.
- 독립 읽기 전용 리뷰에 수정 필요 지적 없음. 선두 시간 인덱스 부재에 따른 전체스캔·운영 부하 한계는 안내에 기록했다. 브라우저 시각 렌더링과 원격CI는 미실행이다. 커밋·push 없음.
