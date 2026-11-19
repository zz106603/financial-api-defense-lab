package lab.financialapi.detection;

import java.util.UUID;

import lab.financialapi.identity.PrincipalType;

/** 같은 집계 창에서 횟수·계좌 수 임계값을 모두 충족한 인증 주체. */
public record DetectionCandidate(
        PrincipalType principalType,
        UUID principalId,
        int violationCount,
        int distinctResourceCount) { }
