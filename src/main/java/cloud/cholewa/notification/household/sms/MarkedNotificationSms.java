package cloud.cholewa.notification.household.sms;

import cloud.cholewa.notification.household.members.HouseholdRegistryClient;
import cloud.cholewa.notification.model.NotificationLevel;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * The SMS of an alert or an info its publisher marked with the header {@code sms} (HAS-335): the
 * message, with its level in front, to the **administrators** of the household with a phone - at home
 * or not, the people who have to act on a lost grid. One SMS each, one after the other.
 * <p>
 * The administrators are read from the registry at most every {@link #ADMINISTRATORS_CACHED_FOR}; a
 * failed read is not kept, so the next marked message asks again. Nothing is retried: an SMS sent
 * twice costs twice and reaches somebody twice ({@link SmsApiClient}).
 * <p>
 * **It never fails**: it is sent after the Discord post of the same message, and whatever goes wrong
 * here - no administrator with a phone, the registry not answering, SMSAPI refusing - is logged and
 * the message is acknowledged. The text of the message is in every line that says no SMS went out,
 * so the log still holds what nobody was texted.
 */
@Slf4j
public class MarkedNotificationSms {

    static final Duration ADMINISTRATORS_CACHED_FOR = Duration.ofMinutes(10);

    private final SmsApiClient client;
    private final Mono<List<SmsRecipients.Recipient>> administrators;

    MarkedNotificationSms(final HouseholdRegistryClient registry, final SmsApiClient client) {
        this.client = client;
        this.administrators = Mono.defer(registry::members)
            .map(SmsRecipients::administratorsOf)
            .doOnNext(found -> log.info(
                "Administrators of the household with a phone read from the registry: {}", found.size()))
            .cache(found -> ADMINISTRATORS_CACHED_FOR, failure -> Duration.ZERO, () -> Duration.ZERO);
    }

    public Mono<Void> send(final NotificationLevel level, final String message) {
        final String text = SmsText.of(level, message);
        return administrators
            .filter(found -> !found.isEmpty())
            .switchIfEmpty(Mono.fromRunnable(() -> log.warn(
                "No administrator of the household has a phone: the marked message went to Discord only: {}",
                message)))
            .flatMap(found -> Flux.fromIterable(found)
                .concatMap(recipient -> client.send(recipient.name(), recipient.phone(), text))
                .filter(Boolean::booleanValue)
                .count()
                .doOnNext(sent -> log.info("Marked {} message texted to {} of {} administrator(s): {}",
                    level.name().toLowerCase(Locale.ROOT), sent, found.size(), message)))
            .onErrorResume(failure -> {
                log.error("Marked message not texted - {}: {} - {}",
                    failure.getClass().getSimpleName(), failure.getMessage(), message);
                return Mono.empty();
            })
            .then();
    }
}
