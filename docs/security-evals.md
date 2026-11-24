# 보안 Evals와 검증 하네스

## 목적과 범위

기존 보안 회귀가 알려진 결함을 탐지하는지 확인한다. 정상 제품 소스·테스트 기대값·배포 JAR에 결함을 넣지 않는다. 실제 서명 JWT·합성 Fixture·Testcontainers PostgreSQL을 사용하는 기존 AccountQueryApiIntegrationTest의 세 메서드(12 invocation)를 재사용한다. 개발 DB·.env·로컬 키는 사용하지 않는다.

| 평가 | 격리 복사본의 주입 | 예상 실패 증거 |
|---|---|---|
| EVAL-01 / SEC-03 | readAccount의 고객 소유권 조건 제거 | 고객A→B1, 고객B→A2의404 기대가200으로 실패 |
| EVAL-02 / SEC-05 | 같은 제휴사의 위임 계좌 일치 조건 제거 | X→미위임 A2의404 기대가200으로 실패 |
| EVAL-03 / SEC-07 | readAccount의 revoked_at IS NULL 제거 | 회수 커밋 후 같은 JWT의 새 조회가404 대신200 |
| EVAL-04 / SEC-09 | AccountResponse에 internalNote getter 추가 | 허용4필드 응답의 크기가5로 증가 |

계좌 SQL의 readAccount 구간에만 한 조건을 바꾸므로 다른 조회·권한 변경 SQL까지 무차별 치환하지 않는다. 각 주입 지점은 정확히1회 일치해야 한다. EVAL-04의 추가 값은 합성 문자열이며 실제 민감정보를 만들지 않는다.

## 실행

JDK21·Python3.11+·Docker가 필요하다. 저장소 루트에서 실행한다. Windows는 python, Linux는 python3를 사용한다.

```powershell
python -m unittest discover -s scripts/tests -p test_security_evals.py
python scripts/security_evals.py
```

기준선1회와 변형4회가 순차 실행된다. 매번 허용 목록의 src, gradle, build.gradle/settings.gradle, Wrapper만 임시 복사하고 새 Gradle test를 실행한다. Gradle 빌드 캐시·기존 테스트 결과는 재사용하지 않는다. 각 실행 제한은 기본300초이며 `--timeout-seconds 600`처럼 조절할 수 있다. Windows의 JVM 소켓 문제를 피하는 IPv4 옵션은 평가 자식 프로세스에만 적용한다.

정상 기준선이 통과하지 않으면 결함 실행을 중단한다. 모든4개가 DETECTED이고 원본 소스 fingerprint가 유지될 때만 종료코드0이다. 미탐지나 환경 실패를 허용하는 옵션은 제공하지 않는다. 취약 복사본은 실행 후 제거하고 XML·로그·주입 전후 문자열만 남긴다. 정상 빌드에 Evals를 자동 연결하거나 제품 API를 추가하지 않는다.

## 판정과 결과 보고서

결과는 `build/security-evals/<UTC-run-id>/report.json`, `report.md`와 단계별 gradle.log/junit XML에 저장한다. build 전체가 Git 제외 대상이다. 소스 fingerprint와 기준 Git 커밋을 함께 기록하므로 미커밋 소스도 구분할 수 있다.

| 상태 | 의미 |
|---|---|
| BASELINE PASS | 결함 없는 동일12개 invocation이 모두 통과 |
| DETECTED | 기준선과 같은 테스트 집합이며 해당 결함의 정확한 invocation·AssertionError 유형·예상 메시지만 실패 |
| SURVIVED | 변형이 컴파일·실행됐고 테스트가 모두 통과: 미탐지 |
| INVALID / BASELINE_FAILED | 결과 누락·skip·집합 차이·잘못된 실패·컴파일/DB/환경 오류 또는 기준선 실패 |
| TIMEOUT | 실행 제한 초과. 자식 프로세스 트리를 종료하고 별도 집계 |

Gradle의 exit1만으로 탐지 성공으로 판단하지 않는다. 매개변수 테스트의 XML display name도 주체·계좌·invocation별로 고정해 EVAL-01의 고객 실패와 EVAL-02의 제휴사 실패를 혼동하지 않는다. 결과 형식이나 기존 Fixture가 달라지면 INVALID로 중단하고 manifest를 검토한다. 테스트 기대값을 약화해서 맞추지 않는다.

탐지율의 분모는 항상 계획한4개이며 미실행·오류를 제외해 높이지 않는다. 기준선 오탐은 결함 없는12개 invocation이 모두 PASS일 때0이며, assertion 실패나 환경 오류 등으로 유효한 기준선을 확보하지 못하면 null이다. 기준선 실패를 임의로 오탐으로 단정하지 않는다. 실행 시간은 monotonic clock 실측값이다. 이 수치는 알려진4개 주입에 대한 자동 테스트 결과이며 일반적인 취약점 탐지 정확도가 아니다.

Agent의 결함 식별 정확도·자동 수정 성공률·재수정 횟수·토큰 사용량은 이 도구가 측정하지 않으므로 null로 기록한다. 이번 구현의 독립 리뷰와 Agent 수정 활동은 자동 탐지율과 구분해 작업 결과에 기록한다. 토큰·시간 추정치를 실측값으로 기재하지 않는다.

## CI와 하네스 운영

- 기존 CI의 clean build·전체 보안 회귀는 그대로 유지한다.
- 별도 Security Evals 워크플로는 workflow_dispatch로 수동 실행한다. 도구 단위 검증→정상 기준선→4개 주입→결과 보존 순서이며 실패를 숨기지 않는다. 읽기 권한만 사용하고 테스트 로그/XML·보고서를7일 보존한다.
- High 변경에는 전체 보안 회귀 결과와 독립 리뷰의 범위·지적·처리 결과를 남긴다. 실행 보고서는 검증 증거이고 리뷰 수행의 대체물이 아니다. 이 워크플로가 저장소 브랜치 보호·리뷰 승인을 설정하는 것은 아니다.
- 실패 시 보고서에서 주입 불일치/예상 assertion/환경 오류를 먼저 구분한다. 해당 원인을 수정한 후 도구 단위 테스트와 영향받은 검증을 재실행한다. 보안 테스트 삭제·기대값 완화·Docker 부재 skip은 사용하지 않는다.

## 이번 실행 결과

2026-11-20 로컬 실행, 기준 커밋 ebb7df2. 정상 src·Gradle 파일의 SHA-256 fingerprint는 3e10106f0c7099e66934739c625a34587710e32a9b39b12ecebd0295551d6c54이며 실행 전후 동일했다.

| 실행 | 결과 | 실행 invocation | 예상 assertion 실패 | 실측 초 |
|---|---|---:|---:|---:|
| BASELINE | PASS | 12 | 0 | 27.938 |
| EVAL-01 | DETECTED | 12 | 고객2건 | 30.203 |
| EVAL-02 | DETECTED | 12 | 제휴사1건 | 38.719 |
| EVAL-03 | DETECTED | 12 | 회수 후1건 | 53.032 |
| EVAL-04 | DETECTED | 12 | 필드 크기1건 | 37.437 |

이 실행의 탐지4/4(100%), 기준선 오탐0, 미탐지·INVALID·timeout·미실행0. 총 실행 실측187.375초. 결과 파일은 `build/security-evals/20261120T035119949034Z/report.json`·report.md와 단계별 XML/로그다. 생성된 취약 복사본은 제거됐다.

- `python -m unittest discover -s scripts/tests -p test_security_evals.py`: 도구12개 통과. 잘못된 assertion·다른 주체의 실패·skip·결과 누락·timeout·주입 불일치·복사 허용 목록·기준선 실패 시 중단을 확인했다.
- `./gradlew.bat build --rerun-tasks --no-daemon '-Dorg.gradle.jvmargs=-Xmx512m -Djava.net.preferIPv4Stack=true'`: 실행 한정 JAVA_TOOL_OPTIONS IPv4 설정과 함께 전체189개 테스트·빌드 통과(실패·오류·건너뜀0). 정상 JAR의 원래 인가 조건과 추가 필드/도구 미포함을 확인했다.
- 첫 정상 기준선은12개 PASS였지만 도구가 Gradle의 매개변수 display name을 잘못 예상해 INVALID로 기록했다. 실제 XML 형식의 회귀 테스트 RED→GREEN으로 수정했다. 별도 시도에서 Windows Gradle 소켓 오류는 INVALID로 남기고 성공에 포함하지 않았다. 최종 위 실행은 모두 완료됐다.
- 독립 읽기 전용 리뷰의 timeout 집계 누락·결함별 invocation 구분 부족을 수정하고 회귀 테스트를 추가했다. 추가 리뷰의 오류 판정 테스트 집합 유지와 기준선 오탐 문구도 반영했다. 재리뷰에서 추가 블로커는 없었다.

위2026-11-20 로컬 검증 시점에는 원격 워크플로를 실행하지 않았다. 이후 원격 실행 결과는 [MVP 검증 현황](mvp-status.md)에 별도로 기록한다. Agent 자동 분석·수정 비교 실험, 일반화된 취약점 탐지율 측정은 미실행이며 토큰·Agent 식별 정확도·재수정 횟수는 미측정이다.
