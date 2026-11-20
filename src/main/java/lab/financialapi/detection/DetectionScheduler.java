package lab.financialapi.detection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** 탐지 실패를 요청 처리로 전파하지 않고 다음 주기 실행을 유지한다. */
public class DetectionScheduler {
    private static final Logger log = LoggerFactory.getLogger(DetectionScheduler.class);
    private final DetectionService detection;

    public DetectionScheduler(DetectionService detection) {
        this.detection = detection;
    }

    @Scheduled(fixedRateString = "#{@detectionTiming.pollMillis}", initialDelayString = "#{@detectionTiming.pollMillis}")
    public void run() {
        try {
            detection.detect();
        } catch (RuntimeException error) {
            log.error("Security detection execution failed");
        }
    }
}
