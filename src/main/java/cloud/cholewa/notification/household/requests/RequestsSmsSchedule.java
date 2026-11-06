package cloud.cholewa.notification.household.requests;

import cloud.cholewa.commons.scheduling.ScheduledPass;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * The first {@code @Scheduled} task of this service (HAS-410): the SMS of the household requests at
 * {@code household.requests.send-at} in {@code household.requests.zone} - 08:00 of the house. Only with
 * {@code household.requests.enabled}; under the test profile nothing is scheduled at all
 * ({@code SchedulingConfig}). Bounded at {@link #LIMIT} (cholewa-commons {@code ScheduledPass}): a call that
 * hangs never holds the scheduler. The pass itself never fails.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnBooleanProperty("household.requests.enabled")
@EnableConfigurationProperties(RequestsProperties.class)
class RequestsSmsSchedule {

    static final Duration LIMIT = Duration.ofMinutes(2);

    private final RequestsSmsPass pass;

    @Scheduled(cron = "${household.requests.send-at}", zone = "${household.requests.zone}")
    Mono<Void> send() {
        return ScheduledPass.bounded("household requests SMS", LIMIT, () -> Mono.defer(pass::run).then());
    }
}
