package cloud.cholewa.notification.household.sms;

import cloud.cholewa.notification.household.members.HouseholdRegistryClient;
import cloud.cholewa.notification.household.members.HouseholdRegistryClient.Member;
import cloud.cholewa.notification.household.members.PresentMembersClient;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SmsRecipientsTest {

    private static final List<Member> REGISTRY = List.of(
        new Member("Ola", "+48500000001", true, "resident"),
        new Member("Jan", "+48500000002", true, "admin"),
        new Member("Ewa", "+48500000003", false, "resident"),
        new Member("Bez", null, true, "resident"),
        new Member("Daleko", "+48500000005", true, "admin"));

    @Test
    void should_text_the_members_at_home_who_are_switched_on_and_have_a_phone_whatever_the_case() {
        assertThat(SmsRecipients.recipientsOf(List.of("ola", "JAN", "Ewa", "Bez", "Nieznany"), REGISTRY))
            .extracting(SmsRecipients.Recipient::name)
            .containsExactly("Ola", "Jan");
    }

    // the smoke test texts the owner, at home or not, never every member
    @Test
    void should_name_the_administrators_with_a_phone_for_the_smoke_test() {
        assertThat(SmsRecipients.administratorsOf(REGISTRY))
            .extracting(SmsRecipients.Recipient::name)
            .containsExactly("Jan", "Daleko");
    }

    @Test
    void should_never_print_a_number() {
        assertThat(new SmsRecipients.Recipient("Ola", "+48500000001")).hasToString("Recipient[Ola]");
    }

    // the SMS and the footer of Discord ask for one message at the same moment
    @Test
    void should_ask_the_two_services_once_for_the_reads_of_one_minute() {
        final AtomicInteger asked = new AtomicInteger();
        final PresentMembersClient present = mock(PresentMembersClient.class);
        final HouseholdRegistryClient registry = mock(HouseholdRegistryClient.class);
        when(present.present()).thenReturn(Mono.fromCallable(() -> {
            asked.incrementAndGet();
            return List.of("Ola");
        }));
        when(registry.members()).thenReturn(Mono.just(REGISTRY));
        final SmsRecipients sut = new SmsRecipients(present, registry);

        sut.recipients().as(StepVerifier::create).expectNextCount(1).verifyComplete();
        sut.recipients().as(StepVerifier::create).expectNextCount(1).verifyComplete();

        assertThat(asked).hasValue(1);
    }

    // never kept: the next message asks again
    @Test
    void should_not_keep_a_failed_read() {
        final AtomicInteger asked = new AtomicInteger();
        final PresentMembersClient present = mock(PresentMembersClient.class);
        final HouseholdRegistryClient registry = mock(HouseholdRegistryClient.class);
        when(present.present()).thenReturn(Mono.defer(() -> asked.incrementAndGet() == 1
            ? Mono.error(new IllegalStateException("presence-service answered 503"))
            : Mono.just(List.of("Ola"))));
        when(registry.members()).thenReturn(Mono.just(REGISTRY));
        final SmsRecipients sut = new SmsRecipients(present, registry);

        sut.recipients().as(StepVerifier::create).expectErrorMessage("presence-service answered 503").verify();
        sut.recipients().as(StepVerifier::create)
            .assertNext(found -> assertThat(found).extracting(SmsRecipients.Recipient::name).containsExactly("Ola"))
            .verifyComplete();
    }
}
