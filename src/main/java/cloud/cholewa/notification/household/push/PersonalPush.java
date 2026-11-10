package cloud.cholewa.notification.household.push;

import cloud.cholewa.home.model.PushSubscription;
import cloud.cholewa.notification.household.PersonalMessage;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/**
 * A push notification for one member of the household (HAS-416, the reminders and the announcements of the
 * household requests): to every browser that member subscribed, **whether at home or not** - unlike the household
 * transport, which asks presence-service who is there. The member is asked for by the name the registry spells;
 * a name with a comma cannot be (the read of database-service splits on commas) and counts as no subscription.
 * <p>
 * What it came to is a {@link Result}, never an error: delivered when one browser took it, no subscription when
 * there was none to post to (none subscribed, none on an allowed push service), failed when there were some and
 * not one took it - or the read of database-service failed. 404 / 410 removes the subscription, as for the
 * household transport. Nothing is retried; the caller decides what comes next (an SMS, the next pass).
 */
@Slf4j
public class PersonalPush {

    public enum Result { DELIVERED, NO_SUBSCRIPTION, FAILED }

    private final PushSubscriptionsClient subscriptions;
    private final WebPushSender sender;
    private final AllowedEndpoints allowedEndpoints;
    private final JsonMapper jsonMapper;

    PersonalPush(
        final PushSubscriptionsClient subscriptions,
        final WebPushSender sender,
        final List<String> allowedHosts,
        final JsonMapper jsonMapper
    ) {
        this.subscriptions = subscriptions;
        this.sender = sender;
        this.allowedEndpoints = new AllowedEndpoints(allowedHosts);
        this.jsonMapper = jsonMapper;
    }

    public Mono<Result> send(final String member, final PersonalMessage message) {
        return Mono.just(member)
            .filter(name -> !name.isBlank() && !name.contains(","))
            .flatMap(name -> subscriptions.ofMembers(List.of(name)))
            .flatMap(found -> post(member, found, message))
            .defaultIfEmpty(Result.NO_SUBSCRIPTION)
            .onErrorResume(failure -> {
                log.warn("The push subscriptions of [{}] could not be read: {} - {}", member,
                    failure.getClass().getSimpleName(), failure.getMessage());
                return Mono.just(Result.FAILED);
            });
    }

    private Mono<Result> post(final String member, final List<PushSubscription> found, final PersonalMessage message) {
        final byte[] payload = PushPayload.personal(message, jsonMapper);
        return Flux.fromIterable(found)
            .flatMap(subscription -> Mono.justOrEmpty(allowedEndpoints.of(subscription))
                .flatMap(endpoint -> sender.send(endpoint, subscription, payload))
                .flatMap(outcome -> Mono.just(outcome)
                    .filter(WebPushSender.Outcome.GONE::equals)
                    .flatMap(gone -> removeGone(subscription).thenReturn(gone))
                    .defaultIfEmpty(outcome)))
            .collectList()
            .map(outcomes -> resultOf(member, outcomes));
    }

    private static Result resultOf(final String member, final List<WebPushSender.Outcome> outcomes) {
        final long delivered = outcomes.stream().filter(WebPushSender.Outcome.DELIVERED::equals).count();
        log.info("Push for [{}]: {} of {} browser(s) took it", member, delivered, outcomes.size());
        if (delivered > 0) {
            return Result.DELIVERED;
        }
        return outcomes.contains(WebPushSender.Outcome.FAILED) ? Result.FAILED : Result.NO_SUBSCRIPTION;
    }

    private Mono<Void> removeGone(final PushSubscription subscription) {
        return subscriptions.remove(subscription.getEndpoint())
            .onErrorResume(throwable -> {
                log.warn("A push subscription the push service dropped could not be removed: {} - {}",
                    throwable.getClass().getSimpleName(), throwable.getMessage());
                return Mono.empty();
            });
    }
}
