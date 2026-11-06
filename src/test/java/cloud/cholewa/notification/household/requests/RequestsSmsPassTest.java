package cloud.cholewa.notification.household.requests;

import cloud.cholewa.home.model.MemberRequest;
import cloud.cholewa.notification.household.members.HouseholdRegistryClient;
import cloud.cholewa.notification.household.members.HouseholdRegistryClient.Member;
import cloud.cholewa.notification.household.sms.SmsApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//The pass of the SMS of the household requests (HAS-410): one SMS per recipient, only the accepted ones marked,
//a recipient without a phone left pending, a failure of one never stopping the next
class RequestsSmsPassTest {

    private final PendingRequestsClient pending = mock(PendingRequestsClient.class);
    private final HouseholdRegistryClient registry = mock(HouseholdRegistryClient.class);
    private final SmsApiClient sms = mock(SmsApiClient.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<SmsApiClient> smsProvider = mock(ObjectProvider.class);
    private RequestsSmsPass sut;

    private static MemberRequest request(final long id, final String member, final String text, final String author) {
        return MemberRequest.builder().id(id).member(member).text(text).author(author).build();
    }

    @BeforeEach
    void setUp() {
        when(smsProvider.getIfAvailable()).thenReturn(sms);
        when(registry.members()).thenReturn(Mono.just(List.of(
            new Member("Liwia", "+48500000001", true, "resident"),
            new Member("Borys", "+48500000002", true, "resident"),
            new Member("Ewa", null, true, "resident"))));
        when(pending.markSent(any())).thenAnswer(call -> Mono.just((long) ((List<?>) call.getArgument(0)).size()));
        sut = new RequestsSmsPass(pending, registry, smsProvider);
    }

    @Test
    void should_text_every_recipient_once_and_mark_what_was_accepted() {
        when(pending.pending()).thenReturn(Mono.just(List.of(
            request(1, "Borys", "chleb", "Liwia"),
            request(2, "liwia", "papier toaletowy", "Borys"),
            request(3, "Liwia", "żel pod prysznic", "Borys"))));
        when(sms.send(anyString(), anyString(), anyString())).thenReturn(Mono.just(true));

        sut.run().as(StepVerifier::create).expectNext(new RequestsSmsPass.Outcome(2, 3)).verifyComplete();

        verify(sms).send("Borys", "+48500000002", "Zapotrzebowanie na dziś: chleb (Liwia)");
        verify(sms).send("Liwia", "+48500000001",
            "Zapotrzebowanie na dziś: papier toaletowy (Borys), żel pod prysznic (Borys)");
        //marked recipient by recipient, at once after the SMS
        verify(pending).markSent(List.of(1L));
        verify(pending).markSent(List.of(2L, 3L));
    }

    //SMSAPI refused one: its requests stay pending, the other is marked
    @Test
    void should_mark_only_the_requests_whose_sms_was_accepted() {
        when(pending.pending()).thenReturn(Mono.just(List.of(
            request(1, "Borys", "chleb", "Liwia"),
            request(2, "Liwia", "mleko", "Borys"))));
        when(sms.send(eq("Borys"), anyString(), anyString())).thenReturn(Mono.just(false));
        when(sms.send(eq("Liwia"), anyString(), anyString())).thenReturn(Mono.just(true));

        sut.run().as(StepVerifier::create).expectNext(new RequestsSmsPass.Outcome(1, 1)).verifyComplete();

        verify(pending).markSent(List.of(2L));
    }

    //a member without a phone, or not in the registry: one WARN, the requests stay pending, the next is texted
    @Test
    void should_leave_the_requests_of_a_member_without_a_phone_pending_and_go_on() {
        when(pending.pending()).thenReturn(Mono.just(List.of(
            request(1, "Ewa", "chleb", "Liwia"),
            request(2, "Gone", "mleko", "Liwia"),
            request(3, "Borys", "masło", "Liwia"))));
        when(sms.send(anyString(), anyString(), anyString())).thenReturn(Mono.just(true));

        sut.run().as(StepVerifier::create).expectNext(new RequestsSmsPass.Outcome(1, 1)).verifyComplete();

        verify(sms, never()).send(eq("Ewa"), any(), anyString());
        verify(pending).markSent(List.of(3L));
    }

    @Test
    void should_go_on_after_an_sms_that_failed() {
        when(pending.pending()).thenReturn(Mono.just(List.of(
            request(1, "Borys", "chleb", "Liwia"),
            request(2, "Liwia", "mleko", "Borys"))));
        when(sms.send(eq("Borys"), anyString(), anyString())).thenReturn(Mono.error(new IllegalStateException("boom")));
        when(sms.send(eq("Liwia"), anyString(), anyString())).thenReturn(Mono.just(true));

        sut.run().as(StepVerifier::create).expectNext(new RequestsSmsPass.Outcome(1, 1)).verifyComplete();
    }

    //texted and not marked: logged with the ids, texted again tomorrow - the pass still completes
    @Test
    void should_complete_when_the_marking_fails() {
        when(pending.pending()).thenReturn(Mono.just(List.of(request(1, "Borys", "chleb", "Liwia"))));
        when(sms.send(anyString(), anyString(), anyString())).thenReturn(Mono.just(true));
        doReturn(Mono.error(new IllegalStateException("database down"))).when(pending).markSent(any());

        sut.run().as(StepVerifier::create).expectNext(new RequestsSmsPass.Outcome(1, 0)).verifyComplete();
    }

    @Test
    void should_do_nothing_without_a_pending_request_or_without_the_sms() {
        when(pending.pending()).thenReturn(Mono.just(List.of()));

        sut.run().as(StepVerifier::create).expectNext(RequestsSmsPass.Outcome.NOTHING).verifyComplete();
        verify(registry, never()).members();

        when(smsProvider.getIfAvailable()).thenReturn(null);
        when(pending.pending()).thenReturn(Mono.just(List.of(request(1, "Borys", "chleb", "Liwia"))));
        sut.run().as(StepVerifier::create).expectNext(RequestsSmsPass.Outcome.NOTHING).verifyComplete();
        verify(sms, never()).send(anyString(), anyString(), anyString());
        verify(pending, never()).markSent(any());
    }

    //one pass at a time: the manual trigger during the 08:00 pass reads nothing and texts nobody twice
    @Test
    void should_do_nothing_while_another_pass_runs() {
        final Sinks.One<List<MemberRequest>> slow = Sinks.one();
        when(pending.pending()).thenReturn(slow.asMono());
        when(sms.send(anyString(), anyString(), anyString())).thenReturn(Mono.just(true));

        final java.util.concurrent.atomic.AtomicReference<RequestsSmsPass.Outcome> first =
            new java.util.concurrent.atomic.AtomicReference<>();
        sut.run().subscribe(first::set);

        sut.run().as(StepVerifier::create).expectNext(RequestsSmsPass.Outcome.NOTHING).verifyComplete();

        slow.tryEmitValue(List.of(request(1, "Borys", "chleb", "Liwia")));
        org.assertj.core.api.Assertions.assertThat(first.get()).isEqualTo(new RequestsSmsPass.Outcome(1, 1));
        verify(sms, times(1)).send(anyString(), anyString(), anyString());
        //and once it is over, the next pass runs again
        when(pending.pending()).thenReturn(Mono.just(List.of()));
        sut.run().as(StepVerifier::create).expectNext(RequestsSmsPass.Outcome.NOTHING).verifyComplete();
        verify(pending, times(2)).pending();
    }

    //an accepted SMS counts even when its requests carry no id to mark
    @Test
    void should_count_an_accepted_sms_whatever_the_marking() {
        when(pending.pending()).thenReturn(Mono.just(List.of(
            MemberRequest.builder().member("Borys").text("chleb").author("Liwia").build())));
        when(sms.send(anyString(), anyString(), anyString())).thenReturn(Mono.just(true));

        sut.run().as(StepVerifier::create).expectNext(new RequestsSmsPass.Outcome(1, 0)).verifyComplete();
        verify(pending, never()).markSent(any());
    }

    //database-service not read: nothing texted, nothing marked, the pass completes
    @Test
    void should_complete_when_the_pending_requests_cannot_be_read() {
        when(pending.pending()).thenReturn(Mono.error(new IllegalStateException("database-service answered 503")));

        sut.run().as(StepVerifier::create).expectNext(RequestsSmsPass.Outcome.NOTHING).verifyComplete();
        verify(sms, never()).send(anyString(), anyString(), anyString());
    }
}
