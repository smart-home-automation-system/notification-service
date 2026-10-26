package cloud.cholewa.notification.household.sms;

import cloud.cholewa.home.model.HouseholdNotification;
import cloud.cholewa.notification.household.HouseholdMessage;
import cloud.cholewa.notification.household.HouseholdTransport;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * The household message by SMS to the members at home (HAS-275): one SMS per member a message, the words of
 * the Discord message cut to one SMS ({@link SmsText}). Nobody at home is no failure - Discord has the
 * message. A read of who is at home that fails is: the dispatcher logs it at ERROR, and no SMS goes out.
 * The transport fails when there was somebody to text and not one SMS was accepted.
 * <p>
 * The SMS go one after the other, not at once: SMSAPI counts the requests of an account, and a household
 * has a handful of members.
 */
@Slf4j
class SmsHouseholdTransport implements HouseholdTransport {

    private final SmsRecipients recipients;
    private final SmsApiClient client;

    SmsHouseholdTransport(final SmsRecipients recipients, final SmsApiClient client) {
        this.recipients = recipients;
        this.client = client;
    }

    @Override
    public String name() {
        return "sms";
    }

    @Override
    public Mono<Void> deliver(final HouseholdNotification notification, final HouseholdMessage message) {
        return recipients.recipients()
            .filter(found -> !found.isEmpty())
            .switchIfEmpty(Mono.fromRunnable(() -> log.info(
                "Nobody of the household with a phone is at home: no SMS for [{}] of [{}]",
                notification.getEvent(), notification.getSubject())))
            .flatMap(found -> send(found, SmsText.of(message), notification));
    }

    private Mono<Void> send(
        final List<SmsRecipients.Recipient> found,
        final String text,
        final HouseholdNotification notification
    ) {
        return Flux.fromIterable(found)
            .concatMap(recipient -> client.send(recipient.name(), recipient.phone(), text))
            .filter(Boolean::booleanValue)
            .count()
            .doOnNext(sent -> log.info("SMS for [{}] of [{}]: {} of {} member(s) texted",
                notification.getEvent(), notification.getSubject(), sent, found.size()))
            .filter(sent -> sent == 0)
            .flatMap(none -> Mono.error(new IllegalStateException("No SMS was accepted by SMSAPI")));
    }
}
