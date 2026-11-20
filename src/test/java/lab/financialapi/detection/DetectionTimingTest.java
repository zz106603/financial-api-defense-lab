package lab.financialapi.detection;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DetectionTimingTest {
    @Test
    void 별도_설정이_없으면_5분과_30초를_유지한다() {
        var environment = new MockEnvironment();
        var timing = new DetectionTiming(environment);
        assertThat(timing.windowSeconds()).isEqualTo(300);
        assertThat(timing.cooldownSeconds()).isEqualTo(300);
        assertThat(timing.getPollMillis()).isEqualTo(30000);
    }

    @Test
    void 환경별_시간_설정이_서비스와_스케줄러에_적용된다() {
        var environment = new MockEnvironment();
        assertThat(new DetectionTiming(environment).windowSeconds()).isEqualTo(300);
        environment.withProperty("detection.window-seconds", "30")
                .withProperty("detection.cooldown-seconds", "30")
                .withProperty("detection.poll-millis", "2000");
        var timing = new DetectionTiming(environment);
        assertThat(timing.windowSeconds()).isEqualTo(30);
        assertThat(timing.cooldownSeconds()).isEqualTo(30);
        assertThat(timing.getPollMillis()).isEqualTo(2000);
    }

    @Test
    void 시간이_0이나_음수이면_거부한다() {
        var environment = new MockEnvironment().withProperty("detection.window-seconds", "0");
        assertThatThrownBy(() -> new DetectionTiming(environment)).isInstanceOf(IllegalArgumentException.class);
    }
}
