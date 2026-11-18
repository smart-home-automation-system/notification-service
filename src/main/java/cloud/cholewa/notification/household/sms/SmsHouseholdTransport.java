package cloud.cholewa.notification.household.sms;

import cloud.cholewa.home.model.HouseholdNotification;
import cloud.cholewa.notification.household.HouseholdMessage;
import cloud.cholewa.notification.household.HouseholdMessages;
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
 * <p>
 * The smog (HAS-430) is never texted - an event-level rule, not a setting (owner, 2026-11-12): Discord and Web Push
 * tell the household, an SMS costs and the smog is no emergency. Nor the doorbell and the gate (HAS-451).
 * <p>
 * Of a garage gate (HAS-472) only the first message of an alarm is texted, to the members named for it at home or
 * not ({@link SmsRecipients#of}); the warning, a repeat and the end never ({@link HouseholdMessages#isTexted}).
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

    //the dispatcher hands it no smog, no event of the intercom (HAS-451) and of a garage gate only the first message
    //of an alarm (HAS-472), and says so in the log
    @Override
    public boolean carries(final HouseholdNotification notification) {
        return HouseholdMessages.isTexted(notification);
    }

    @Override
    public Mono<Void> deliver(final HouseholdNotification notification, final HouseholdMessage message) {
        return recipients.of(notification)
            .filter(found -> !found.isEmpty())
            .switchIfEmpty(Mono.fromRunnable(() -> nobodyToText(notification)))
            .flatMap(found -> send(found, textOf(notification, message), notification));
    }

    //the sentence of a garage gate does not name the gate: its title goes before it
    private static String textOf(final HouseholdNotification notification, final HouseholdMessage message) {
        return HouseholdMessages.isGarageGate(notification) ? SmsText.titled(message) : SmsText.of(message);
    }

    //nobody at home is no failure; nobody of the members named for a garage alarm is a setting to fix
    private static void nobodyToText(final HouseholdNotification notification) {
        if (HouseholdMessages.isGarageAlarm(notification)) {
            log.warn("None of household.garage.sms-members is switched on with a phone: no SMS for [{}] of [{}]",
                notification.getEvent(), notification.getSubject());
            return;
        }
        log.info("Nobody of the household with a phone is at home: no SMS for [{}] of [{}]",
            notification.getEvent(), notification.getSubject());
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
