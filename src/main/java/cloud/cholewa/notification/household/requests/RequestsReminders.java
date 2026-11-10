package cloud.cholewa.notification.household.requests;

import cloud.cholewa.home.model.MemberRequest;
import cloud.cholewa.notification.household.members.HouseholdRegistryClient;
import cloud.cholewa.notification.household.members.HouseholdRegistryClient.Member;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * The reminders and the announcements of the household requests (HAS-416, epic HAS-407, owner 2026-11-10), from
 * the lists of database-service >= 0.24.0 - two passes, each one at a time ({@link AtomicBoolean}, the rule of
 * {@link RequestsSmsPass}):
 * <ul>
 *   <li>{@link #announceDone()} - every minute: the requests ticked off, one message to each author (database-service
 *   counts a tick of the author as announced already);</li>
 *   <li>{@link #evening()} - at 20:00 of the house: one reminder to each recipient listing their open texted
 *   requests not reminded today, then one message to each author of the requests open {@code stale-after} after
 *   their SMS - told once.</li>
 * </ul>
 * Delivered by {@link MemberMessenger} (push, an SMS without one). What a message carried is marked at once after
 * it was delivered - a pass cut off by its bound leaves nothing delivered unmarked; a marking that fails is an
 * ERROR with the ids, and that message goes again (an announcement the next minute, a reminder the next evening).
 * A member not in the registry, switched off, or with no browser and no phone, never can be told: the
 * announcements to such an author are marked with a WARN (or they would be asked for every minute forever), a
 * reminder is only a WARN - tomorrow is another one. A message that failed is not marked and goes again. Never
 * fails; the text is never logged - the member, the counts and the ids are.
 */
@Slf4j
@Component
public class RequestsReminders {

    private final PendingRequestsClient requests;
    private final HouseholdRegistryClient registry;
    private final MemberMessenger messenger;
    private final RequestsProperties properties;
    private final Clock clock;
    private final AtomicBoolean announcing = new AtomicBoolean(false);
    private final AtomicBoolean evening = new AtomicBoolean(false);

    RequestsReminders(
        final PendingRequestsClient requests,
        final HouseholdRegistryClient registry,
        final MemberMessenger messenger,
        final RequestsProperties properties,
        @Qualifier("requestsClock") final Clock clock
    ) {
        this.requests = requests;
        this.registry = registry;
        this.messenger = messenger;
        this.properties = properties;
        this.clock = clock;
    }

    /** What a pass did: the messages delivered and the requests marked. */
    public record Outcome(long messages, long marked) {

        static final Outcome NOTHING = new Outcome(0, 0);

        Outcome plus(final Outcome other) {
            return new Outcome(messages + other.messages, marked + other.marked);
        }
    }

    //who a list is told to, how its message is worded, what it is marked, whether an unreachable one is too, and
    //which of its requests are marked without a word
    private record Kind(String name, Function<MemberRequest, String> addressee,
                        BiFunction<String, List<MemberRequest>, RequestsReminderText.Composed> words,
                        String mark, boolean markUnreachable, Predicate<MemberRequest> silent) {
    }

    private static final Kind DONE = new Kind("announcement of the requests done", MemberRequest::getAuthor,
        (author, list) -> RequestsReminderText.done(list), "done-announced", true, request -> false);
    private static final Kind REMINDER = new Kind("reminder of the open requests", MemberRequest::getMember,
        RequestsReminderText::reminder, "reminded", false, request -> false);
    //a request written for oneself is reminded every evening already: still open, it is marked, never told
    private static final Kind STALE = new Kind("announcement of the requests still open", MemberRequest::getAuthor,
        (author, list) -> RequestsReminderText.stale(list), "stale-announced", true, RequestsReminders::own);

    public Mono<Outcome> announceDone() {
        return once(announcing, DONE.name(), () -> requests.doneToAnnounce().flatMap(found -> tell(DONE, found)));
    }

    public Mono<Outcome> evening() {
        return once(evening, "evening of the household requests", () -> Mono.defer(() -> {
            final LocalDateTime now = LocalDateTime.now(clock);
            return requests.toRemind(now.toLocalDate().atStartOfDay())
                .flatMap(found -> tell(REMINDER, found))
                .onErrorResume(failure -> failed(REMINDER.name(), failure))
                .flatMap(reminded -> requests.stale(now.minus(properties.staleAfter()))
                    .flatMap(found -> tell(STALE, found))
                    .onErrorResume(failure -> failed(STALE.name(), failure))
                    .map(reminded::plus));
        }));
    }

    private Mono<Outcome> once(final AtomicBoolean running, final String what, final Supplier<Mono<Outcome>> pass) {
        return Mono.fromSupplier(() -> running.compareAndSet(false, true))
            .filter(Boolean::booleanValue)
            .flatMap(acquired -> Mono.defer(pass)
                .onErrorResume(failure -> failed(what, failure))
                .doFinally(signal -> running.set(false)))
            .switchIfEmpty(Mono.fromSupplier(() -> {
                log.warn("The {} runs already: this pass does nothing", what);
                return Outcome.NOTHING;
            }));
    }

    private Mono<Outcome> tell(final Kind kind, final List<MemberRequest> found) {
        final List<MemberRequest> silent = found.stream().filter(kind.silent()).toList();
        final List<MemberRequest> told = found.stream().filter(kind.silent().negate()).toList();
        return Mono.just(silent)
            .filter(list -> !list.isEmpty())
            .flatMap(list -> mark(kind, "their own authors", list))
            .map(marked -> new Outcome(0, marked))
            .defaultIfEmpty(Outcome.NOTHING)
            .flatMap(quiet -> Mono.just(told)
                .filter(list -> !list.isEmpty())
                .flatMap(list -> registry.members().flatMap(members -> tellEach(kind, list, members)))
                .defaultIfEmpty(Outcome.NOTHING)
                .map(quiet::plus));
    }

    private static boolean own(final MemberRequest request) {
        return key(request.getAuthor()).equals(key(request.getMember()));
    }

    private Mono<Outcome> tellEach(final Kind kind, final List<MemberRequest> found, final List<Member> members) {
        final Map<String, Member> byName = members.stream()
            .filter(member -> member.name() != null)
            .collect(Collectors.toMap(member -> key(member.name()), Function.identity(), (first, second) -> first));
        return Flux.fromIterable(byAddressee(kind, found).entrySet())
            .concatMap(group -> tellOne(kind, group.getValue(), byName.get(group.getKey()),
                kind.addressee().apply(group.getValue().getFirst())))
            .reduce(Outcome.NOTHING, Outcome::plus)
            .doOnNext(outcome -> log.info("Household requests, {}: {} message(s) delivered, {} request(s) marked",
                kind.name(), outcome.messages(), outcome.marked()));
    }

    private Mono<Outcome> tellOne(final Kind kind, final List<MemberRequest> group, final Member member, final String name) {
        return Mono.justOrEmpty(member)
            .filter(Member::switchedOn)
            .flatMap(found -> {
                final RequestsReminderText.Composed composed = kind.words().apply(found.name(), group);
                return messenger.deliver(found, composed.message())
                    .flatMap(delivery -> delivered(kind, group, composed.carried(), found.name(), delivery));
            })
            .switchIfEmpty(Mono.defer(() -> unreachable(kind, group, name, "not a member switched on")))
            .onErrorResume(failure -> {
                log.error("The {} for [{}] failed - {}: {}", kind.name(), name, failure.getClass().getSimpleName(),
                    failure.getMessage());
                return Mono.just(Outcome.NOTHING);
            });
    }

    private Mono<Outcome> delivered(final Kind kind, final List<MemberRequest> group, final List<MemberRequest> carried,
                                    final String name, final MemberMessenger.Delivery delivery) {
        return switch (delivery) {
            case PUSH, SMS -> mark(kind, name, carried).map(marked -> new Outcome(1, marked))
                .doOnNext(outcome -> log.info("Household requests, {} for [{}] by {}: {} of {} carried", kind.name(), name,
                    delivery, carried.size(), group.size()));
            case UNREACHABLE -> unreachable(kind, group, name, "no browser subscribed and no phone");
            case FAILED -> Mono.fromSupplier(() -> {
                log.warn("Household requests, {} for [{}] not delivered: {} request(s) told again later", kind.name(), name,
                    group.size());
                return Outcome.NOTHING;
            });
        };
    }

    private Mono<Outcome> unreachable(final Kind kind, final List<MemberRequest> group, final String name, final String why) {
        log.warn("Household requests, {} for [{}] cannot be delivered ({}): {} request(s){}", kind.name(), name, why,
            group.size(), kind.markUnreachable() ? " marked as told" : "");
        return Mono.just(kind.markUnreachable())
            .filter(Boolean::booleanValue)
            .flatMap(marking -> mark(kind, name, group))
            .map(marked -> new Outcome(0, marked))
            .defaultIfEmpty(Outcome.NOTHING);
    }

    //delivered: it counts whatever the marking comes to
    private Mono<Long> mark(final Kind kind, final String name, final List<MemberRequest> carried) {
        final List<Long> ids = carried.stream().map(MemberRequest::getId).filter(Objects::nonNull).toList();
        return Mono.just(ids)
            .filter(known -> !known.isEmpty())
            .flatMap(known -> requests.mark(kind.mark(), known)
                .onErrorResume(failure -> {
                    log.error("The household requests {} of [{}] were told and not marked {} - told again: {}", known, name,
                        kind.mark(), failure.getMessage());
                    return Mono.just(0L);
                }))
            .defaultIfEmpty(0L);
    }

    private static Mono<Outcome> failed(final String what, final Throwable failure) {
        log.error("The {} failed - {}: {}", what, failure.getClass().getSimpleName(), failure.getMessage());
        return Mono.just(Outcome.NOTHING);
    }

    //by addressee, in the order database-service answers - whatever the case of a name; none named, none told
    private static Map<String, List<MemberRequest>> byAddressee(final Kind kind, final List<MemberRequest> found) {
        return found.stream()
            .filter(request -> kind.addressee().apply(request) != null)
            .collect(Collectors.groupingBy(request -> key(kind.addressee().apply(request)), LinkedHashMap::new,
                Collectors.toList()));
    }

    private static String key(final String name) {
        return Optional.ofNullable(name).orElse("").toLowerCase(Locale.ROOT);
    }
}
