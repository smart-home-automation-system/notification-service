package cloud.cholewa.notification.household.push;

import cloud.cholewa.home.model.HouseholdEvent;
import cloud.cholewa.notification.household.members.HouseholdRegistryClient;
import cloud.cholewa.notification.household.members.PresentMembersClient;
import cloud.cholewa.home.model.HouseholdNotification;
import cloud.cholewa.home.model.PushSubscription;
import cloud.cholewa.notification.household.HouseholdMessage;
import cloud.cholewa.notification.household.HouseholdMessages;
import cloud.cholewa.notification.household.HouseholdTransport;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

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
 * <p>
 * The doorbell (HAS-451) goes to every switched-on member of the registry, at home or not - somebody away may let
 * the caller in; the opening of the gate is never pushed (the dispatcher logs it as not carried).
 * <p>
 * A garage gate left open (HAS-472) goes the same way, to every switched-on member at home or not - nobody at home is
 * one of its alarms; its end ({@code garage gate closed}) is never pushed.
 * <p>
 * Both events of a fire alarm (HAS-462) go to every switched-on member, at home or not - the end too, so that it
 * replaces the alarm on the phones that showed it.
 * <p>
 * The three events of load shedding (HAS-522) go to the members at home - the house without the grid is where they are;
 * the end replaces the start silently.
 */
@Slf4j
class WebPushHouseholdTransport implements HouseholdTransport {

    //the bound of the read of database-service (@Size(max = 20) on members)
    static final int MEMBERS_PER_READ = 20;

    private final PresentMembersClient presentMembers;
    private final HouseholdRegistryClient registry;
    private final PushSubscriptionsClient subscriptions;
    private final WebPushSender sender;
    private final AllowedEndpoints allowedEndpoints;
    private final JsonMapper jsonMapper;

    WebPushHouseholdTransport(
        final PresentMembersClient presentMembers,
        final HouseholdRegistryClient registry,
        final PushSubscriptionsClient subscriptions,
        final WebPushSender sender,
        final List<String> allowedHosts,
        final JsonMapper jsonMapper
    ) {
        this.presentMembers = presentMembers;
        this.registry = registry;
        this.subscriptions = subscriptions;
        this.sender = sender;
        this.allowedEndpoints = new AllowedEndpoints(allowedHosts);
        this.jsonMapper = jsonMapper;
    }

    @Override
    public String name() {
        return "push";
    }

    @Override
    public boolean carries(final HouseholdNotification notification) {
        return notification.getEvent() != HouseholdEvent.GATE_OPENED
            && notification.getEvent() != HouseholdEvent.GARAGE_GATE_CLOSED;
    }

    @Override
    public Mono<Void> deliver(final HouseholdNotification notification, final HouseholdMessage message) {
        return recipientsOf(notification)
            .map(WebPushHouseholdTransport::askable)
            .filter(members -> !members.isEmpty())
            .switchIfEmpty(Mono.fromRunnable(() -> log.info("Nobody of the household to reach: no push for [{}] of [{}]",
                notification.getEvent(), notification.getSubject())))
            //database-service takes at most MEMBERS_PER_READ names a read: more at home are asked in turn
            .flatMapMany(members -> Flux.fromIterable(members).buffer(MEMBERS_PER_READ))
            .concatMap(subscriptions::ofMembers)
            .flatMapIterable(found -> found)
            .collectList()
            .flatMap(found -> send(found, notification, message));
    }

    //the doorbell, a garage gate left open and a fire alarm: everybody switched on in the registry; every other event:
    //who is at home
    private Mono<List<String>> recipientsOf(final HouseholdNotification notification) {
        return Mono.just(notification)
            .filter(WebPushHouseholdTransport::toEverybody)
            .flatMap(everybody -> registry.members())
            .map(members -> members.stream()
                .filter(HouseholdRegistryClient.Member::switchedOn)
                .map(HouseholdRegistryClient.Member::name)
                .toList())
            .switchIfEmpty(Mono.defer(presentMembers::present));
    }

    private static boolean toEverybody(final HouseholdNotification notification) {
        return notification.getEvent() == HouseholdEvent.DOORBELL_RINGING
            || HouseholdMessages.isGarageGate(notification)
            || HouseholdMessages.isFire(notification);
    }

    private Mono<Void> send(
        final List<PushSubscription> found,
        final HouseholdNotification notification,
        final HouseholdMessage message
    ) {
        final byte[] payload = PushPayload.of(notification, message, jsonMapper);

        return Flux.fromIterable(found)
            .flatMap(subscription -> Mono.justOrEmpty(allowedEndpoints.of(subscription))
                .flatMap(endpoint -> sender.send(endpoint, subscription, payload))
                //a dropped subscription is removed from the registry, and still counted as what it came to
                .flatMap(outcome -> Mono.just(outcome)
                    .filter(WebPushSender.Outcome.GONE::equals)
                    .flatMap(gone -> removeGone(subscription).thenReturn(gone))
                    .defaultIfEmpty(outcome))
                .defaultIfEmpty(WebPushSender.Outcome.FAILED))
            .collectList()
            .doOnNext(outcomes -> log.info("Push for [{}] of [{}]: {} of {} browser(s) took it",
                notification.getEvent(), notification.getSubject(), delivered(outcomes), outcomes.size()))
            //a household whose browsers all failed: told once, as a failed transport
            .filter(outcomes -> delivered(outcomes) == 0 && outcomes.contains(WebPushSender.Outcome.FAILED))
            .flatMap(outcomes -> Mono.error(new IllegalStateException("No browser at home took the push message")));
    }

    private static long delivered(final List<WebPushSender.Outcome> outcomes) {
        return outcomes.stream().filter(WebPushSender.Outcome.DELIVERED::equals).count();
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
