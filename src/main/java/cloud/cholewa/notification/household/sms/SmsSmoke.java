package cloud.cholewa.notification.household.sms;

import cloud.cholewa.notification.household.HouseholdMessage;
import cloud.cholewa.notification.household.members.HouseholdRegistryClient;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The SMS of the smoke test (`GET /skippy?category=household&sms=true`, HAS-275): the household message
 * of a made-up device to the **administrators** of the household, at home or not - one SMS to the owner,
 * never to every member (an SMS costs and wakes somebody). Answers how many were accepted.
 */
@Slf4j
public class SmsSmoke {

    private final HouseholdRegistryClient registry;
    private final SmsApiClient client;

    SmsSmoke(final HouseholdRegistryClient registry, final SmsApiClient client) {
        this.registry = registry;
        this.client = client;
    }

    public Mono<Long> sendToAdministrators(final HouseholdMessage message) {
        final String text = SmsText.of(message);
        return registry.members()
            .map(SmsRecipients::administratorsOf)
            .flatMapMany(Flux::fromIterable)
            .concatMap(recipient -> client.send(recipient.name(), recipient.phone(), text))
            .filter(Boolean::booleanValue)
            .count()
            .doOnNext(sent -> log.info("Smoke test: {} SMS accepted by SMSAPI", sent));
    }
}
