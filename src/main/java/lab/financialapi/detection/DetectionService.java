package lab.financialapi.detection;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 요청 처리와 분리된 탐지 배치. 억제 상태 갱신과 알림 저장은 함께 커밋·롤백한다. */
@Service
public class DetectionService {
    private final DetectionMapper detection;
    private final SecurityAlertMapper alerts;
    private final DetectionTiming timing;

    public DetectionService(DetectionMapper detection, SecurityAlertMapper alerts, DetectionTiming timing) {
        this.detection = detection;
        this.alerts = alerts;
        this.timing = timing;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public int detect() {
        var end = detection.currentTime();
        int created = 0;
        for (var candidate : detection.candidatesInWindow(end, timing.windowSeconds())) {
            var claimedAt = detection.claimAfterCooldown(
                    "R-01", candidate.principalType(), candidate.principalId(), end, timing.cooldownSeconds());
            if (claimedAt.isEmpty()) {
                continue;
            }

            var alert = new SecurityAlert(
                    UUID.randomUUID(),
                    "R-01",
                    candidate.principalType(),
                    candidate.principalId(),
                    end.minusSeconds(timing.windowSeconds()),
                    end,
                    candidate.violationCount(),
                    candidate.distinctResourceCount(),
                    claimedAt.get());
            if (alerts.insert(alert) != 1) {
                throw new IllegalStateException("Security alert was not persisted");
            }
            created++;
        }
        return created;
    }
}
