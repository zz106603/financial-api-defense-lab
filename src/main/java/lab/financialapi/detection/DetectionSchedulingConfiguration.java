package lab.financialapi.detection;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "detection.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class DetectionSchedulingConfiguration {
    @Bean
    DetectionScheduler detectionScheduler(DetectionService detection) {
        return new DetectionScheduler(detection);
    }
}
