# MVP 검증 현황과 남은 범위

확인 기준일: 2026-11-24. 기존 완료 기준과 보안 정책은 변경하지 않는다. 제품 기능의 검증 상태와 Agent 평가·운영 검증의 미완료를 구분한다.

## 원격 검증 대상과 증거

대상은 원격 main의 fb0b05548150703e6e6ad30ae4bb2939b923006b(PR #10 병합)이다. 문서 작업 브랜치의 미커밋 변경을 원격에서 검증한 것은 아니다. 기존 성공 기록 외에 같은 커밋을 workflow_dispatch로 새로 실행했다.

| 작업 | 이번 실행 | 결과 |
|---|---|---|
| CI | [45324564725](https://github.com/zz106603/financial-api-defense-lab/actions/runs/45324564725) | success. clean build·테스트189개, 실패/오류/skip0 |
| Security Evals | [45324567917](https://github.com/zz106603/financial-api-defense-lab/actions/runs/45324567917) | success. 도구12개·기준선12개 PASS, 결함4/4 DETECTED |

실행 완료 후 JUnit XML과 JSON 보고서를 다운로드해 대조했다. CI는16개 suite·189개 테스트, 실패/오류/skip0이다. Evals는 각 단계의 동일12개 invocation, 예상 실패2/1/1/1건, 기준선 오탐0, 미탐지/INVALID/timeout/미실행0이다. 변형의 assertion 실패 합계5건은 평가가 의도한 탐지 증거이며 정상 회귀의 실패와 구분한다. 원본 source_unchanged=true, Agent 관련 지표는 null이다.

| 평가 | 정책 | 원격 판정 | 실행 테스트 | 예상 assertion 실패 |
|---|---|---|---:|---:|
| BASELINE | 정상 대조 | PASS | 12 | 0 |
| EVAL-01 | SEC-03 고객 소유권 | DETECTED | 12 | 2 |
| EVAL-02 | SEC-05 위임 계좌 | DETECTED | 12 | 1 |
| EVAL-03 | SEC-07 회수 권한 | DETECTED | 12 | 1 |
| EVAL-04 | SEC-09 응답 필드 | DETECTED | 12 | 1 |

보고서의 원격 Evals 총 실측 시간은104.41064558초다. Linux 복사본 fingerprint는4af6a5531d4579d7a94d507a5b2285cf63c6148a0ec801c05bc9f59707e57284이며 실행 전후 동일했다. 이전 Windows 실행의 바이트 단위 fingerprint와 직접 같다고 가정하지 않는다.

CI 아티팩트 test-reports(ID14889306201), Evals 아티팩트 security-evals(ID14889251617)는 2026-12-01 UTC 만료 예정이다. 다운로드한 로컬 증거는 Git 제외된 build/remote-verification/45324564725 및45324567917 아래에 있다. Evals JSON은45324567917/20261124T041107429316Z/report.json이다. GitHub 아티팩트 보존 기간은7일이며, 저장소 문서는 실행 링크·커밋·핵심 결과를 장기 요약으로 남긴다.

## 기존 MVP 완료 기준별 판정

| project-overview.md의 완료 기준 | 상태 | 검증 근거·제한 |
|---|---|---|
| 정상·비정상 접근 제어 테스트 통과 | 정의한 범위 완료 | 원격 전체189개·JWT·조회 API·SQL 테스트. 시험한 합성 시나리오에 대한 결과 |
| 권한 회수 완료 후 새 무단 조회 차단 | 정의한 범위 완료 | 원격 ConcurrencyIntegrationTest, AccessGrantConcurrencyIntegrationTest, 회수 전후 같은 JWT 요청 |
| 접근·권한 변경 감사 이벤트 기록 | 정의한 범위 완료 | 원격 AccountQueryApiIntegrationTest, AccessGrantApiIntegrationTest, RequestFailureAuditIntegrationTest. INSERT/커밋 실패·독립 거부 감사 포함 |
| 탐지 규칙 충족 시 보안 이벤트 생성 | 정의한 범위 완료 | 원격 DetectionIntegrationTest·DetectionApiIntegrationTest. 기본5분 경계/억제·원자성·경합. 별도 로컬 dev 실제30초 흐름 |
| 결함 주입 평가4개 이상 탐지 결과 측정 | 완료 | 원격 EVAL-01~04 모두 탐지·기준선 통과. 자동 테스트의 알려진 결함 탐지력 평가 |
| High 변경에 강화된 검증 적용 | 운영 절차 적용 | AGENTS.md의 관련/전체 테스트·독립 리뷰와 기존 작업 기록. 저장소 보호 설정으로 승인까지 자동 강제됐다는 뜻은 아님 |
| 테스트·리뷰·Agent 작업 효율 결과 기록 | 부분 완료 | 테스트·독립 리뷰·Evals 시간은 기록. Agent 작업 성공률·토큰·식별/수정 성과·하네스 전후 효율 비교는 미측정 |
| 주요 설계 결정·검증 결과 문서화 | 현재 범위 완료 | architecture.md, testing-ci.md, local-development.md, security-evals.md와 본 문서. 후속 검증 결과는 추가 기록 필요 |

현재 문서의 모든 MVP 기준을 완전히 달성했다고 판정하지 않는다. 금융 API 기능·기존 보안 회귀·결함4개 자동 평가와 Agent 작업 효율 측정은 서로 다른 완료 항목이다. Agent 평가 확장은 앞서 후속으로 미뤘으며 기준 삭제나 임의 완료 처리로 대체하지 않는다.

## 보안 요구사항과 테스트 근거

| 요구사항 | 주요 기존 테스트 |
|---|---|
| SEC-01·10 인증/검증 실패 거부 | JwtAuthenticationIntegrationTest, JwtConfigurationTest, AccountQueryApiIntegrationTest |
| SEC-02~06 소유권·위임 계좌·조회 범위 | AccountQueryApiIntegrationTest, FinancialReadProjectionIntegrationTest, PersistenceIntegrationTest |
| SEC-07 권한 회수 가시성·동시성 | ConcurrencyIntegrationTest, AccessGrantConcurrencyIntegrationTest, AccountQueryApiIntegrationTest |
| SEC-08 타인 권한 변경 차단·감사 원자성 | AccessGrantApiIntegrationTest, AccessGrantConcurrencyIntegrationTest |
| SEC-09 응답 필드 제한 | AccountQueryApiIntegrationTest와 EVAL-04 |
| SEC-11 반복 무단 접근 탐지 | DetectionIntegrationTest, DetectionApiIntegrationTest, DetectionTimingIntegrationTest |
| SEC-12 감사 민감정보 비노출 | AccountQueryApiIntegrationTest, AccessGrantApiIntegrationTest, RequestFailureAuditIntegrationTest, ResourceReferenceGeneratorTest |

정확한 테스트·정책은 [보안 요구사항](security-requirements.md), [테스트 안내](testing-ci.md), [평가 판정](security-evals.md)을 기준으로 한다. 통과는 정의된 테스트 범위의 증거이며 모든 가능한 공격·운영 환경의 안전 보장은 아니다.

## 남은 항목과 미검증 범위

### 프로젝트 전체 완료 판단에 남는 항목

- H07 및 완료 기준의 Agent 작업 효율 측정: 비교 조건·성공 기준·측정 가능한 지표를 정하고 실제 결과를 기록해야 한다. 토큰을 확보하지 못하면 미측정으로 남긴다.
- Agent의 결함 식별·수정 능력 평가는 사용자가 미뤄 둔 선택적 후속 확장이다. 기존 H07 측정 공백과는 구분하며 이를 MVP 필수 기능으로 새로 승격하지 않는다. 현재 Evals는 테스트 탐지력만 평가하고, 해당 확장을 수행할 경우 정답/채점기 접근 분리·정상 대조군·독립 채점이 필요하다.
- 하네스 개선 전후 효과 비교는 미실행이다. 자동 테스트 탐지4/4나 Evals 실행 시간을 Agent 성능·개선 효과로 해석하지 않는다.

### 검증·운영 한계 및 별도 확인 사항

- 브랜치 보호·필수 상태 검사·리뷰 승인 강제 설정은 이번 작업에서 조회·변경하지 않았다. CI 통과와 독립 리뷰 수행 기록이 해당 설정의 증거는 아니다.
- 원격 CI 실패 주입·복구 훈련과 Agent의 실패 수정 능력은 미검증이다. 기존 로컬 오류 분석·재검증 기록과 구분한다.
- 실제5분을 기다리는 dev 재시연은 이번 작업에서 하지 않는다. 기본5분은 PostgreSQL의 제어된 시간 경계 테스트, 실제 스케줄러 흐름은 기존 dev30초 검증으로 구분한다.
- 운영 부하·연결 풀 적정성, 탐지 지연/SLA, 창을 벗어난 늦은 커밋·장기 중단 소급 탐지, 운영 보존 기간·복구·감사 위변조 방지는 미검증/별도 범위다.
- SAST·DAST·의존성 취약점 검사와 다양한 DB/OS/Java 버전 조합의 검증은 이번 결과에 포함하지 않는다. CI 대상은 Ubuntu24.04·Java21·PostgreSQL18.1이다.
- 실금융기관·실거래 데이터·외부 관제·인증 서버/JWKS 자동 순환·토큰 즉시 폐기는 기존 MVP 제외 범위다. 이를 이번 작업의 필수 완료 항목으로 추가하지 않는다.

## 이번 문서 작업의 검증

제품 코드·테스트·워크플로·보안 정책 변경은 없다. 원격 실행 증거 대조와 문서 링크·diff 일관성을 확인했다. 문서 변경만을 위해 로컬 전체 빌드를 반복하지 않았다.
