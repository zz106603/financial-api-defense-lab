package lab.financialapi.detection;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** 기본 탐지 정책은 유지하고 환경별 설정을 서비스·SQL·스케줄러에 동일하게 적용한다. */
@Component
public class DetectionTiming {
    private final int windowSeconds;
    private final int cooldownSeconds;
    private final int pollMillis;

    public DetectionTiming(Environment environment) {
        windowSeconds = positive(environment, "window-seconds", 300);
        cooldownSeconds = positive(environment, "cooldown-seconds", 300);
        pollMillis = positive(environment, "poll-millis", 30000);
    }

    private static int positive(Environment environment, String name, int fallback) {
        int value = environment.getProperty("detection." + name, Integer.class, fallback);
        if (value <= 0) {
            throw new IllegalArgumentException("Detection timing must be positive: " + name);
        }
        return value;
    }

    public int windowSeconds() {
        return windowSeconds;
    }

    public int cooldownSeconds() {
        return cooldownSeconds;
    }

    public int getPollMillis() {
        return pollMillis;
    }
}
