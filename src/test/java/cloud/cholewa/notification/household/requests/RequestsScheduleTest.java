package cloud.cholewa.notification.household.requests;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

//The schedule of the SMS of the household requests (HAS-410): application.yaml as the home profile reads it - 08:00
//in the zone of the house - nothing scheduled while it is off, and the pass bounded
class RequestsScheduleTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withInitializer(context -> context.getEnvironment().getPropertySources()
            .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME))
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withUserConfiguration(Pass.class, RequestsSmsSchedule.class);

    @Test
    void should_text_at_eight_in_the_zone_of_the_house() {
        contextRunner
            .withPropertyValues("spring.profiles.active=home")
            .run(context -> {
                final RequestsProperties properties = context.getBean(RequestsProperties.class);
                assertThat(properties).isEqualTo(new RequestsProperties(true, "0 0 8 * * *", ZoneId.of("Europe/Warsaw")));
                assertThat(context).hasSingleBean(RequestsSmsSchedule.class);
                //the day the clocks go back: 08:00 of the house is 07:00 UTC after it, 06:00 the day before
                final ZonedDateTime next = CronExpression.parse(properties.sendAt())
                    .next(ZonedDateTime.of(LocalDateTime.of(2026, 10, 25, 3, 0), properties.zone()));
                assertThat(next.toInstant()).hasToString("2026-10-25T07:00:00Z");
            });
    }

    @Test
    void should_schedule_nothing_while_it_is_off() {
        contextRunner
            .withPropertyValues("spring.profiles.active=home", "household.requests.enabled=false")
            .run(context -> assertThat(context).doesNotHaveBean(RequestsSmsSchedule.class));
        contextRunner
            .withPropertyValues("spring.profiles.active=home,local")
            .run(context -> assertThat(context).doesNotHaveBean(RequestsSmsSchedule.class));
    }

    @Test
    void should_refuse_a_zone_that_is_none() {
        contextRunner
            .withPropertyValues("spring.profiles.active=home", "household.requests.zone=Mars/Olympus")
            .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void should_read_the_cron_and_the_zone_from_the_properties() throws NoSuchMethodException {
        final Scheduled scheduled = RequestsSmsSchedule.class.getDeclaredMethod("send").getAnnotation(Scheduled.class);

        assertThat(scheduled.cron()).isEqualTo("${household.requests.send-at}");
        assertThat(scheduled.zone()).isEqualTo("${household.requests.zone}");
    }

    //a pass that hangs is cut off within the bound; the schedule never fails
    @Test
    void should_bound_the_pass() {
        final RequestsSmsPass pass = mock(RequestsSmsPass.class);
        when(pass.run()).thenReturn(Mono.never());
        final RequestsSmsSchedule schedule = new RequestsSmsSchedule(pass);

        StepVerifier.withVirtualTime(schedule::send)
            .thenAwait(Duration.ofMinutes(2).plusSeconds(1))
            .verifyComplete();
    }

    @Configuration(proxyBeanMethods = false)
    static class Pass {

        @Bean
        RequestsSmsPass requestsSmsPass() {
            return mock(RequestsSmsPass.class);
        }
    }
}
