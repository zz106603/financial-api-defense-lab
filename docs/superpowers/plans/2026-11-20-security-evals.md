# 보안 Evals 및 최소 하네스 구현 계획

## 범위와 설계

- High 위험도. 기존 정상 제품 코드·보안 기대값은 변경하지 않는다. 커밋·push 없음.
- scripts/security_evals.py: Python3.11+ 표준 라이브러리 CLI. src·Gradle 설정/Wrapper만 실행별 임시 공간으로 복사한다. 정상 기준선 후 EVAL-01~04를 각각 독립 복사본에 단일 주입한다.
- src/test의 기존 AccountQueryApiIntegrationTest를 재사용한다. 세 테스트 선택으로 정상12개 invocation과 정상 허용 대조를 확인한다. 원본 SQL·DTO·테스트와 정상 JAR에는 결함이 들어가지 않는다.
- 주입은 readAccount의 고객 소유권/위임 계좌 범위/회수 조건 누락, AccountResponse의 추가 응답 필드다. 주입 지점이 정확히1개일 때만 적용하며 소스 변화로 일치하지 않으면 중단한다.
- JUnit XML의 testcase·failure type·예상 메시지를 근거로 판정한다. 단순 Gradle 비정상 종료를 탐지 성공으로 취급하지 않는다. 정상 통과·정상 결과의 실패, 미탐지, 컴파일/환경 오류·timeout·결과누락을 구분한다. baseline과 mutant의 실행 테스트 집합이 같아야 한다.
- build/security-evals/<run-id> 아래 JSON·Markdown·단계별 로그/XML을 남긴다. 보고서는 기준 커밋·소스 fingerprint·실행 시간·탐지/미탐지·기준선 오탐·예상 assertion 증거를 포함한다. Agent 식별/수정 정확도·토큰은 자동 테스트와 구분하고 미측정은 null이다.
- .github/workflows/security-evals.yaml: 기존 정상 CI를 유지한 별도 수동 평가 작업. 도구 단위 테스트·Evals·결과 보존을 실행하며 실패를 숨기지 않는다. 저장소 브랜치 보호나 리뷰 승인을 자동으로 변경하지 않는다.

## 순차 구현·검증

1. 도구 테스트 RED: XML의 정확한 assertion/오류/미탐지/누락·skip·테스트집합 차이 판정, 주입 일치 개수·복사 허용 목록 검증을 먼저 작성한다.
2. CLI·4개 주입·fingerprint·timeout·보고서를 구현하고 도구 테스트 GREEN을 확인한다.
3. 실제 PostgreSQL 기준선12개 PASS → 주입4개를 실행해 예상 보안 assertion 실패 및 정상 허용 테스트 유지 여부를 확인한다. 컴파일·환경 오류는 원인을 수정하고 재실행한다.
4. 정상 전체 보안 회귀·빌드, 배포 JAR 결함 혼입 검사와 원본 fingerprint 불변을 확인한다.
5. 독립 읽기 전용 리뷰 후 타당한 지적을 수정·관련 재검증한다. 실제 결과·미검증 범위는 docs/security-evals.md와 docs/testing-ci.md에 기록한다.

## 남기는 파일

새 파일: scripts/security_evals.py, scripts/tests/test_security_evals.py, docs/security-evals.md, .github/workflows/security-evals.yaml, 본 계획.

기존 문서 수정: README.md, docs/architecture.md, docs/testing-ci.md, docs/security-requirements.md. 불필요한 서버·관리 API·프런트엔드·Agent 자동수정 시스템·외부 보안 도구는 추가하지 않는다.

## 실행 기록

- clean main에서 feat/security-evals 생성. 제품 src·빌드 설정·기존 보안 테스트 변경 없음.
- 도구 미구현 import 실패 RED →8개 GREEN. 실제 기준선12개 PASS의 Gradle parameterized XML 이름 불일치는 INVALID로 기록하고 테스트를 먼저 추가해 RED→GREEN으로 수정했다. 별도 Windows Gradle Bad address는 환경 INVALID로 구분했으며 검증을 약화하지 않았다.
- 독립 리뷰의 timeout 요약 누락과 다른 주체의 실패를 탐지로 오인할 가능성을 수정했다. 정확한 invocation별 실패 집합·timeout 별도 집계·프로세스 트리 종료·기준선 실패 중단 보고서를 검증하는 도구12개 통과. 후속 테스트 품질·문서 문구 보완도 반영했다.
- 최종 실제 run 20261120T035119949034Z: 기준선12 PASS, 변형별12개 실행, EVAL-01~04 DETECTED. assertion 실패는2/1/1/1개, 원본 fingerprint 불변. 총187.375초 실측. 취약 임시 복사본 제거 완료.
- 정상 build --rerun-tasks 및 전체189개 테스트 통과(실패·오류·건너뜀0). 정상 JAR의 원래 인가 조건·응답 DTO 유지와 도구 미포함 확인.
- 원격 수동 워크플로는 미실행. Agent 자동 식별·수정 성과와 토큰은 미측정. 커밋·push 없음.
