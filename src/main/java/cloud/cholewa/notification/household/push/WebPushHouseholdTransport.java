package cloud.cholewa.notification.household.push;

import cloud.cholewa.home.model.HouseholdNotification;
import cloud.cholewa.home.model.PushSubscription;
import cloud.cholewa.notification.household.HouseholdMessage;
import cloud.cholewa.notification.household.HouseholdTransport;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The household message as a push notification on the phones of the members at home (HAS-276): the
 * installed application gets it with the application closed (iOS 16.4+, permission granted, HAS-284).
 * <p>
 * Who gets it: the members presence-service says are at home, each of their browsers that subscribed
 * (database-service). Nobody at home is no failure - the Discord channel has the message. A
 * presence-service or database-service that does not answer is: the dispatcher logs it at ERROR, and
 * no push goes out, never one to everybody instead.
 * <p>
 * A subscription whose endpoint is not on a push service of {@code household.push.allowed-hosts} is
 * skipped with a warning naming the host - the registry takes any https address a browser gives. A push
 * service answering 404 or 410 dropped the subscription: it is removed from the registry, and a removal
 * that fails is logged and tried again by the next message that finds it. The transport fails only when
 * there was somebody to reach and not one browser took the message.
 */
@Slf4j
class WebPushHouseholdTransport implements HouseholdTransport {

    //the bound of the read of database-service (@Size(max = 20) on members)
    static final int MEMBERS_PER_READ = 20;

    private final PresentMembersClient presentMembers;
    private final PushSubscriptionsClient subscriptions;
    private final WebPushSender sender;
    private final List<String> allowedHosts;
    private final JsonMapper jsonMapper;

    WebPushHouseholdTransport(
        final PresentMembersClient presentMembers,
        final PushSubscriptionsClient subscriptions,
        final WebPushSender sender,
        final List<String> allowedHosts,
        final JsonMapper jsonMapper
    ) {
        this.presentMembers = presentMembers;
        this.subscriptions = subscriptions;
        this.sender = sender;
        this.allowedHosts = allowedHosts.stream().map(host -> host.strip().toLowerCase(Locale.ROOT)).toList();
        this.jsonMapper = jsonMapper;
    }

    @Override
    public String name() {
        return "push";
    }

    @Override
    public Mono<Void> deliver(final HouseholdNotification notification, final HouseholdMessage message) {
        return presentMembers.present()
            .map(WebPushHouseholdTransport::askable)
            .flatMap(members -> {
                if (members.isEmpty()) {
                    log.info("Nobody of the household is at home: no push for [{}] of [{}]",
                        notification.getEvent(), notification.getSubject());
                    return Mono.empty();
                }
                //database-service takes at most MEMBERS_PER_READ names a read: more at home are asked in turn
                return Flux.fromIterable(members)
                    .buffer(MEMBERS_PER_READ)
                    .concatMap(subscriptions::ofMembers)
                    .flatMapIterable(found -> found)
                    .collectList()
                    .flatMap(found -> send(found, notification, message));
            });
    }

    private Mono<Void> send(
        final List<PushSubscription> found,
        final HouseholdNotification notification,
        final HouseholdMessage message
    ) {
        final byte[] payload = PushPayload.of(notification, message, jsonMapper);
        final String topic = PushPayload.tagOf(notification);

        return Flux.fromIterable(found)
            .flatMap(subscription -> allowedEndpoint(subscription)
                .map(endpoint -> sender.send(endpoint, subscription, payload, topic)
                    .flatMap(outcome -> outcome == WebPushSender.Outcome.GONE
                        ? removeGone(subscription).thenReturn(outcome)
                        : Mono.just(outcome)))
                .orElseGet(() -> Mono.just(WebPushSender.Outcome.FAILED)))
            .collectList()
            .flatMap(outcomes -> {
                final long delivered = outcomes.stream().filter(WebPushSender.Outcome.DELIVERED::equals).count();
                log.info("Push for [{}] of [{}]: {} of {} browser(s) took it",
                    notification.getEvent(), notification.getSubject(), delivered, outcomes.size());
                //a household whose browsers all failed: told once, as a failed transport
                return delivered == 0 && outcomes.stream().anyMatch(WebPushSender.Outcome.FAILED::equals)
                    ? Mono.error(new IllegalStateException("No browser at home took the push message"))
                    : Mono.<Void>empty();
            });
    }

    private Optional<URI> allowedEndpoint(final PushSubscription subscription) {
        final URI endpoint;
        try {
            endpoint = URI.create(subscription.getEndpoint());
        } catch (IllegalArgumentException | NullPointerException e) {
            log.warn("A push subscription with an endpoint that is no URI was skipped");
            return Optional.empty();
        }
        final String host = endpoint.getHost() == null ? "" : endpoint.getHost().toLowerCase(Locale.ROOT);
        final boolean allowed = "https".equalsIgnoreCase(endpoint.getScheme())
            && allowedHosts.stream().anyMatch(allowedHost -> host.equals(allowedHost) || host.endsWith("." + allowedHost));
        if (!allowed) {
            log.warn("A push subscription on [{}] was skipped: not a push service of household.push.allowed-hosts", host);
            return Optional.empty();
        }
        return Optional.of(endpoint);
    }

    private Mono<Void> removeGone(final PushSubscription subscription) {
        return subscriptions.remove(subscription.getEndpoint())
            .onErrorResume(throwable -> {
                log.warn("A push subscription the push service dropped could not be removed: {} - {}",
                    throwable.getClass().getSimpleName(), throwable.getMessage());
                return Mono.empty();
            });
    }

    //the read of database-service takes the names comma-separated: a name holding a comma would be read
    //as two - it is left out, and said so
    private static List<String> askable(final List<String> members) {
        return members.stream()
            .filter(member -> {
                if (member.contains(",")) {
                    log.warn("Household member [{}] has a comma in the name and cannot be asked for push subscriptions", member);
                    return false;
                }
                return !member.isBlank();
            })
            .toList();
    }
}
