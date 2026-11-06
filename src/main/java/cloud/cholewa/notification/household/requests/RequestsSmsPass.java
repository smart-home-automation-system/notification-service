package cloud.cholewa.notification.household.requests;

import cloud.cholewa.home.model.MemberRequest;
import cloud.cholewa.notification.household.members.HouseholdRegistryClient;
import cloud.cholewa.notification.household.members.HouseholdRegistryClient.Member;
import cloud.cholewa.notification.household.sms.SmsApiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The SMS of the household requests (HAS-410, epic HAS-407): once a day the pending requests of
 * database-service, one SMS per recipient - at home or not, the owner's decision - and the requests an
 * accepted SMS carried marked sent. SMS only: no Discord, no push, no queue.
 * <ul>
 *   <li>one pass at a time ({@link #running}): the schedule and the manual trigger never read the same pending
 *   requests twice - a second one asked meanwhile does nothing;</li>
 *   <li>the recipients one after the other; a failure of one never stops the next;</li>
 *   <li>a recipient not in the registry, switched off meanwhile or without a phone is one WARN and their
 *   requests stay pending - texted the day the member is reachable;</li>
 *   <li>what an accepted SMS carried is marked sent at once, recipient by recipient - a pass cut off by its
 *   bound never leaves a texted request unmarked ({@link RequestsSmsText}: the rest of a long list waits);</li>
 *   <li>a marking that fails is an ERROR with the ids - the next morning repeats that SMS once (accepted: an
 *   SMS twice beats one never). Nothing is retried, as for every SMS here.</li>
 * </ul>
 * Never fails: what goes wrong is logged and the pass answers what it did. The text is never logged - the
 * member, the counts and the ids are.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RequestsSmsPass {

    private final PendingRequestsClient pendingRequests;
    private final HouseholdRegistryClient registry;
    private final ObjectProvider<SmsApiClient> smsApiClient;
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** What a pass did: the SMS accepted and the requests marked sent. */
    public record Outcome(long sms, long marked) {

        static final Outcome NOTHING = new Outcome(0, 0);

        Outcome plus(final Outcome other) {
            return new Outcome(sms + other.sms, marked + other.marked);
        }
    }

    public Mono<Outcome> run() {
        return Mono.fromSupplier(() -> running.compareAndSet(false, true))
            .filter(Boolean::booleanValue)
            .flatMap(acquired -> texting().doFinally(signal -> running.set(false)))
            .switchIfEmpty(Mono.fromSupplier(() -> {
                log.warn("The household requests are being texted already: this pass does nothing");
                return Outcome.NOTHING;
            }));
    }

    private Mono<Outcome> texting() {
        return Mono.justOrEmpty(smsApiClient.getIfAvailable())
            .switchIfEmpty(Mono.fromRunnable(() -> log.warn(
                "The household requests were not texted: the SMS is switched off (household.sms.enabled)")))
            .flatMap(client -> pendingRequests.pending()
                .filter(pending -> !pending.isEmpty())
                .switchIfEmpty(Mono.fromRunnable(() -> log.info("No household request to text")))
                .flatMap(pending -> registry.members().flatMap(members -> send(client, pending, members))))
            .defaultIfEmpty(Outcome.NOTHING)
            .onErrorResume(failure -> {
                log.error("The household requests were not texted - {}: {}", failure.getClass().getSimpleName(),
                    failure.getMessage());
                return Mono.just(Outcome.NOTHING);
            });
    }

    private Mono<Outcome> send(final SmsApiClient client, final List<MemberRequest> pending, final List<Member> members) {
        final Map<String, Member> byName = members.stream()
            .filter(member -> member.name() != null)
            .collect(Collectors.toMap(member -> key(member.name()), Function.identity(), (first, second) -> first));
        return Flux.fromIterable(byRecipient(pending).entrySet())
            .concatMap(recipient -> sendTo(client, recipient.getValue(), byName.get(recipient.getKey())))
            .reduce(Outcome.NOTHING, Outcome::plus)
            .doOnNext(outcome -> log.info("Household requests texted: {} SMS accepted, {} request(s) marked sent",
                outcome.sms(), outcome.marked()));
    }

    //one SMS and, when it was accepted, the marking of what it carried
    private Mono<Outcome> sendTo(final SmsApiClient client, final List<MemberRequest> requests, final Member member) {
        final String name = requests.getFirst().getMember();
        return Mono.justOrEmpty(member)
            .filter(found -> found.switchedOn() && found.phone() != null && !found.phone().isBlank())
            .switchIfEmpty(Mono.fromRunnable(() -> log.warn(
                "{} household request(s) of [{}] not texted: no member switched on with a phone - they stay pending",
                requests.size(), name)))
            .flatMap(found -> {
                final RequestsSmsText.Composed composed = RequestsSmsText.of(found.name(), requests);
                return client.send(found.name(), found.phone(), composed.text())
                    .filter(Boolean::booleanValue)
                    .flatMap(accepted -> mark(found.name(), composed.carried()))
                    .doOnNext(outcome -> log.info("Household requests of [{}]: {} of {} texted", found.name(),
                        composed.carried().size(), requests.size()));
            })
            .defaultIfEmpty(Outcome.NOTHING)
            .onErrorResume(failure -> {
                log.error("The household requests of [{}] were not texted - {}: {}", name,
                    failure.getClass().getSimpleName(), failure.getMessage());
                return Mono.just(Outcome.NOTHING);
            });
    }

    //the SMS was accepted: it counts whatever the marking comes to
    private Mono<Outcome> mark(final String member, final List<MemberRequest> carried) {
        final List<Long> ids = carried.stream().map(MemberRequest::getId).filter(Objects::nonNull).toList();
        return Mono.just(ids)
            .filter(known -> !known.isEmpty())
            .flatMap(known -> pendingRequests.markSent(known)
                .onErrorResume(failure -> {
                    log.error("The household requests {} of [{}] were texted and not marked sent - texted again "
                        + "tomorrow: {}", known, member, failure.getMessage());
                    return Mono.just(0L);
                }))
            .defaultIfEmpty(0L)
            .map(marked -> new Outcome(1, marked));
    }

    //by recipient as database-service answers them - by member, oldest first - whatever the case of a name
    private static Map<String, List<MemberRequest>> byRecipient(final List<MemberRequest> pending) {
        return pending.stream()
            .filter(request -> request.getMember() != null)
            .collect(Collectors.groupingBy(request -> key(request.getMember()), LinkedHashMap::new, Collectors.toList()));
    }

    private static String key(final String name) {
        return Optional.ofNullable(name).orElse("").toLowerCase(Locale.ROOT);
    }
}
