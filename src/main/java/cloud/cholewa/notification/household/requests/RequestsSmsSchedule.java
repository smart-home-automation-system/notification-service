package cloud.cholewa.notification.household.requests;

import cloud.cholewa.commons.scheduling.ScheduledPass;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * The first {@code @Scheduled} task of this service (HAS-410): the SMS of the household requests at
 * {@code household.requests.send-at} in {@code household.requests.zone} - 08:00 of the house. Since HAS-416 also
 * the announcements of the requests ticked off every {@code household.requests.announce-every} and the evening
 * reminder at {@code household.requests.remind-at} - 20:00 of the house. Only with
 * {@code household.requests.enabled}; under the test profile nothing is scheduled at all
 * ({@code SchedulingConfig}). Each bounded at 2 min (cholewa-commons {@code ScheduledPass}): a call that hangs never
 * holds the scheduler. The announcements are not bounded below their delay on purpose - a fixed delay starts the
 * next pass after this one completed, so they never overlap, and a bound shorter than a push (15 s) or an SMS
 * (20 s) would cut a pass between a delivered message and its marking, and tell it again. The passes themselves
 * never fail.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnBooleanProperty("household.requests.enabled")
class RequestsSmsSchedule {

    static final Duration LIMIT = Duration.ofMinutes(2);

    private final RequestsSmsPass pass;
    private final RequestsReminders reminders;

    @Scheduled(cron = "${household.requests.send-at}", zone = "${household.requests.zone}")
    Mono<Void> send() {
        return ScheduledPass.bounded("household requests SMS", LIMIT, () -> Mono.defer(pass::run).then());
    }

    @Scheduled(fixedDelayString = "${household.requests.announce-every}",
        initialDelayString = "${household.requests.announce-every}")
    Mono<Void> announce() {
        return ScheduledPass.bounded("household requests done", LIMIT, () -> Mono.defer(reminders::announceDone).then());
    }

    @Scheduled(cron = "${household.requests.remind-at}", zone = "${household.requests.zone}")
    Mono<Void> remind() {
        return ScheduledPass.bounded("household requests evening", LIMIT, () -> Mono.defer(reminders::evening).then());
    }
}
