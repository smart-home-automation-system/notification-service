package cloud.cholewa.notification.household.requests;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
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

//The schedule of the household requests (HAS-410, HAS-416): application.yaml as the home profile reads it - 08:00
//and 20:00 in the zone of the house, the announcements every minute - nothing scheduled while it is off, and the
//passes bounded
class RequestsScheduleTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withInitializer(context -> context.getEnvironment().getPropertySources()
            .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME))
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withUserConfiguration(Pass.class, RequestsConfig.class, RequestsSmsSchedule.class);

    @Test
    void should_text_at_eight_in_the_zone_of_the_house() {
        contextRunner
            .withPropertyValues("spring.profiles.active=home")
            .run(context -> {
                final RequestsProperties properties = context.getBean(RequestsProperties.class);
                assertThat(properties).isEqualTo(new RequestsProperties(true, "0 0 8 * * *", "0 0 20 * * *",
                    Duration.ofSeconds(60), Duration.ofDays(3), ZoneId.of("Europe/Warsaw")));
                assertThat(context).hasSingleBean(RequestsSmsSchedule.class);
                //the day the clocks go back: 08:00 of the house is 07:00 UTC after it, 06:00 the day before
                final ZonedDateTime next = CronExpression.parse(properties.sendAt())
                    .next(ZonedDateTime.of(LocalDateTime.of(2026, 10, 25, 3, 0), properties.zone()));
                assertThat(next.toInstant()).hasToString("2026-10-25T07:00:00Z");
            });
    }

    //HAS-416: the evening at 20:00 of the house, also the day the clocks go back
    @Test
    void should_remind_at_eight_in_the_evening_in_the_zone_of_the_house() {
        contextRunner
            .withPropertyValues("spring.profiles.active=home")
            .run(context -> {
                final RequestsProperties properties = context.getBean(RequestsProperties.class);
                final ZonedDateTime next = CronExpression.parse(properties.remindAt())
                    .next(ZonedDateTime.of(LocalDateTime.of(2026, 10, 25, 3, 0), properties.zone()));
                assertThat(next.toInstant()).hasToString("2026-10-25T19:00:00Z");
            });
    }

    //the properties stay bound with the schedule off: the manual triggers read them
    @Test
    void should_bind_the_properties_with_the_schedule_off() {
        contextRunner
            .withPropertyValues("spring.profiles.active=home,local")
            .run(context -> assertThat(context).hasSingleBean(RequestsProperties.class).hasBean("requestsClock"));
    }

    //a bare number of announce-every is seconds and of stale-after days; out of their bounds the start fails
    @Test
    void should_read_bare_numbers_in_their_units_and_refuse_what_is_out_of_bounds() {
        contextRunner
            .withPropertyValues("spring.profiles.active=home", "household.requests.announce-every=30",
                "household.requests.stale-after=2")
            .run(context -> assertThat(context.getBean(RequestsProperties.class))
                .extracting(RequestsProperties::announceEvery, RequestsProperties::staleAfter)
                .containsExactly(Duration.ofSeconds(30), Duration.ofDays(2)));
        contextRunner
            .withPropertyValues("spring.profiles.active=home", "household.requests.announce-every=5s")
            .run(context -> assertThat(context).hasFailed());
        contextRunner
            .withPropertyValues("spring.profiles.active=home", "household.requests.stale-after=0d")
            .run(context -> assertThat(context).hasFailed());
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
        final Scheduled remind = RequestsSmsSchedule.class.getDeclaredMethod("remind").getAnnotation(Scheduled.class);
        assertThat(remind.cron()).isEqualTo("${household.requests.remind-at}");
        assertThat(remind.zone()).isEqualTo("${household.requests.zone}");
    }

    //the delay of the announcements as Spring schedules it: "60s" of application.yaml is a minute, not 60 ms
    @Test
    void should_announce_every_minute() {
        contextRunner
            .withUserConfiguration(Scheduling.class)
            .withPropertyValues("spring.profiles.active=home")
            .run(context -> assertThat(context.getBean(ScheduledTaskHolder.class).getScheduledTasks())
                .map(ScheduledTask::getTask)
                .filteredOn(FixedDelayTask.class::isInstance)
                .map(task -> ((FixedDelayTask) task).getIntervalDuration())
                .containsExactly(Duration.ofMinutes(1)));
    }

    //a pass that hangs is cut off within the bound; the schedule never fails
    @Test
    void should_bound_the_pass() {
        final RequestsSmsPass pass = mock(RequestsSmsPass.class);
        when(pass.run()).thenReturn(Mono.never());
        final RequestsSmsSchedule schedule = new RequestsSmsSchedule(pass, mock(RequestsReminders.class));

        StepVerifier.withVirtualTime(schedule::send)
            .thenAwait(Duration.ofMinutes(2).plusSeconds(1))
            .verifyComplete();
    }

    //HAS-416: both bounded as the morning - the announcements longer than a push and an SMS, never cut between a
    //delivered message and its marking (a fixed delay never overlaps them)
    @Test
    void should_bound_the_reminders() {
        final RequestsReminders reminders = mock(RequestsReminders.class);
        when(reminders.announceDone()).thenReturn(Mono.never());
        when(reminders.evening()).thenReturn(Mono.never());
        final RequestsSmsSchedule schedule = new RequestsSmsSchedule(mock(RequestsSmsPass.class), reminders);

        StepVerifier.withVirtualTime(schedule::announce)
            .thenAwait(Duration.ofMinutes(2).plusSeconds(1))
            .verifyComplete();
        StepVerifier.withVirtualTime(schedule::remind)
            .thenAwait(Duration.ofMinutes(2).plusSeconds(1))
            .verifyComplete();
    }

    @Configuration(proxyBeanMethods = false)
    static class Pass {

        @Bean
        RequestsSmsPass requestsSmsPass() {
            return mock(RequestsSmsPass.class);
        }

        @Bean
        RequestsReminders requestsReminders() {
            final RequestsReminders reminders = mock(RequestsReminders.class);
            when(reminders.announceDone()).thenReturn(Mono.empty());
            when(reminders.evening()).thenReturn(Mono.empty());
            return reminders;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class Scheduling {
    }
}
