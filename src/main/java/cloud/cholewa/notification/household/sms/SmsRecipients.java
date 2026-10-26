package cloud.cholewa.notification.household.sms;

import cloud.cholewa.notification.household.members.HouseholdRegistryClient;
import cloud.cholewa.notification.household.members.HouseholdRegistryClient.Member;
import cloud.cholewa.notification.household.members.PresentMembersClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Who gets the SMS of a household message (HAS-275): the members presence-service says are at home, who
 * are switched on in the registry and have a phone. The names are matched as the registry keeps them unique,
 * whatever the case.
 * <p>
 * **Asked at most once a minute** ({@link #CACHED_FOR}): the SMS and the line of the Discord message that
 * names who was texted ask both, at the same moment, for the same message. A failed read is not kept - the
 * next message asks again - and fails the caller: no SMS goes out then, never one to everybody instead.
 */
public class SmsRecipients {

    static final Duration CACHED_FOR = Duration.ofSeconds(60);

    private final Mono<List<Recipient>> recipients;

    public SmsRecipients(final PresentMembersClient presentMembers, final HouseholdRegistryClient registry) {
        this.recipients = Mono.defer(() -> Mono.zip(presentMembers.present(), registry.members())
                .map(both -> recipientsOf(both.getT1(), both.getT2())))
            .cache(found -> CACHED_FOR, failure -> Duration.ZERO, () -> Duration.ZERO);
    }

    public Mono<List<Recipient>> recipients() {
        return recipients;
    }

    /** The administrators of the household with a phone, at home or not - the SMS of the smoke test. */
    static List<Recipient> administratorsOf(final List<Member> members) {
        return members.stream()
            .filter(member -> member.switchedOn() && member.admin() && hasPhone(member))
            .map(member -> new Recipient(member.name(), member.phone()))
            .toList();
    }

    static List<Recipient> recipientsOf(final List<String> present, final List<Member> members) {
        final Set<String> atHome = present.stream()
            .map(name -> name.toLowerCase(Locale.ROOT))
            .collect(Collectors.toSet());
        return members.stream()
            .filter(member -> member.name() != null && atHome.contains(member.name().toLowerCase(Locale.ROOT)))
            .filter(member -> member.switchedOn() && hasPhone(member))
            .map(member -> new Recipient(member.name(), member.phone()))
            .toList();
    }

    private static boolean hasPhone(final Member member) {
        return member.phone() != null && !member.phone().isBlank();
    }

    /** A member and the number the SMS goes to - the number is never logged. */
    public record Recipient(String name, String phone) {

        @Override
        public String toString() {
            return "Recipient[" + name + "]";
        }
    }
}
