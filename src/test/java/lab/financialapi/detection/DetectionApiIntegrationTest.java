package lab.financialapi.detection;

import java.util.UUID;

import lab.financialapi.identity.JwtTestTokens;
import lab.financialapi.persistence.PostgresIntegrationSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Sql(scripts = {"/fixtures/clear-financial-data.sql", "/fixtures/financial-data.sql"})
@Sql(scripts = "/fixtures/clear-financial-data.sql", executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
@ExtendWith(OutputCaptureExtension.class)
class DetectionApiIntegrationTest extends PostgresIntegrationSupport {
    static final String PREFIX = "/api/v1/accounts/";
    static final String A1 = "30000000-0000-4000-8000-000000000001";
    static final String A2 = "30000000-0000-4000-8000-000000000002";
    static final String B1 = "30000000-0000-4000-8000-000000000003";
    static final String A3 = "30000000-0000-4000-8000-000000000004";
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired DetectionService detection;
    MockMvc mvc;

    @DynamicPropertySource
    static void jwtProperties(DynamicPropertyRegistry registry) {
        JwtTestTokens.registerProperties(registry);
    }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.update("INSERT INTO account SELECT ?, customer_id, bank_name, masked_account_number, balance, created_at "
                + "FROM account WHERE id = ?", UUID.fromString(A3), UUID.fromString(A1));
    }

    @Test
    void 실제_무단_조회_감사에서_SEC11_알림을_생성하고_인증_입력_오류는_집계하지_않는다() throws Exception {
        seedDeniedHttpRequests();
        mvc.perform(get(PREFIX + A1)).andExpect(status().isUnauthorized());
        mvc.perform(get(PREFIX + A1 + "/transactions?size=0")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isBadRequest());
        assertThat(detection.detect()).isEqualTo(1);
        assertThat(detection.detect()).isZero();
        var alert = jdbc.queryForMap("SELECT * FROM security_alert");
        assertThat(alert.get("principal_id")).isEqualTo(UUID.fromString("10000000-0000-4000-8000-000000000002"));
        assertThat(alert.get("violation_count")).isEqualTo(5);
        assertThat(alert.get("distinct_resource_count")).isEqualTo(3);
        assertThat(alert.toString()).doesNotContain(A1, A2, A3, "customer-b", "Bearer", "masked");
        mvc.perform(get(PREFIX + B1).header(HttpHeaders.AUTHORIZATION, bearer())).andExpect(status().isOk());
    }

    @Test
    void 스케줄러_장애는_API를_막지_않고_다음_호출에서_다시_탐지한다(CapturedOutput output) throws Exception {
        seedDeniedHttpRequests();
        var scheduler = new DetectionScheduler(detection);
        jdbc.execute("ALTER TABLE security_alert RENAME TO unavailable_scheduled_alert");
        try {
            scheduler.run();
            assertThat(output.toString()).contains("Security detection execution failed")
                    .doesNotContain("INSERT INTO security_alert", "unavailable_scheduled_alert", "Bearer ");
            mvc.perform(get(PREFIX + B1).header(HttpHeaders.AUTHORIZATION, bearer())).andExpect(status().isOk());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM detection_state", Integer.class)).isZero();
        } finally {
            jdbc.execute("ALTER TABLE unavailable_scheduled_alert RENAME TO security_alert");
        }
        scheduler.run();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM security_alert", Integer.class)).isEqualTo(1);
    }

    @Test
    void 기본_설정의_스케줄러는_실제_탐지_작업을_등록한다() throws Exception {
        seedDeniedHttpRequests();
        new ApplicationContextRunner()
                .withBean(DetectionService.class, () -> detection)
                .withBean(DetectionTiming.class, () -> new DetectionTiming(new org.springframework.core.env.StandardEnvironment()))
                .withUserConfiguration(DetectionSchedulingConfiguration.class)
                .run(application -> {
                    assertThat(application).hasSingleBean(DetectionScheduler.class);
                    var tasks = application.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks();
                    assertThat(tasks).hasSize(1);
                    // 실제 등록된 Runnable을 실행해 Spring의 예약 작업과 서비스 연결까지 검증한다.
                    tasks.iterator().next().getTask().getRunnable().run();
                    assertThat(jdbc.queryForObject("SELECT count(*) FROM security_alert", Integer.class)).isEqualTo(1);
                });
    }

    @Test
    void 자동_실행을_끄면_스케줄러만_없고_탐지_서비스는_직접_실행할_수_있다() throws Exception {
        seedDeniedHttpRequests();
        new ApplicationContextRunner()
                .withBean(DetectionService.class, () -> detection)
                .withBean(DetectionTiming.class, () -> new DetectionTiming(new org.springframework.core.env.StandardEnvironment()))
                .withUserConfiguration(DetectionSchedulingConfiguration.class)
                .withPropertyValues("detection.scheduling.enabled=false")
                .run(application -> {
                    assertThat(application).doesNotHaveBean(DetectionScheduler.class);
                    assertThat(application.getBean(DetectionService.class).detect()).isEqualTo(1);
                });
    }

    private void seedDeniedHttpRequests() throws Exception {
        for (String id : new String[]{A1, A2, A3, A1, A2}) {
            mvc.perform(get(PREFIX + id).header(HttpHeaders.AUTHORIZATION, bearer())).andExpect(status().isNotFound());
        }
    }

    private String bearer() {
        return "Bearer " + JwtTestTokens.token("customer-b", "CUSTOMER");
    }
}
