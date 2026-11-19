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

    public DetectionService(DetectionMapper detection, SecurityAlertMapper alerts) {
        this.detection = detection;
        this.alerts = alerts;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public int detect() {
        var end = detection.currentTime();
        int created = 0;
        for (var candidate : detection.candidates(end)) {
            var claimedAt = detection.claim("R-01", candidate.principalType(), candidate.principalId(), end);
            if (claimedAt.isEmpty()) {
                continue;
            }

            var alert = new SecurityAlert(
                    UUID.randomUUID(),
                    "R-01",
                    candidate.principalType(),
                    candidate.principalId(),
                    end.minusMinutes(5),
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
