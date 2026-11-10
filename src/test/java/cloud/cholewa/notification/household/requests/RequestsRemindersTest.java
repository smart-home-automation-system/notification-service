package cloud.cholewa.notification.household.requests;

import cloud.cholewa.home.model.MemberRequest;
import cloud.cholewa.notification.household.PersonalMessage;
import cloud.cholewa.notification.household.members.HouseholdRegistryClient;
import cloud.cholewa.notification.household.members.HouseholdRegistryClient.Member;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//The reminders and the announcements of the household requests (HAS-416): one message per addressee, only what
//was delivered marked, an author nobody can reach marked as told, a reminder of one never marked, and one pass
//at a time
class RequestsRemindersTest {

    private static final ZoneId HOUSE = ZoneId.of("Europe/Warsaw");
    //20:00 of the house on 10 November
    private static final Clock EVENING = Clock.fixed(Instant.parse("2026-11-10T19:00:00Z"), HOUSE);

    private final PendingRequestsClient requests = mock(PendingRequestsClient.class);
    private final HouseholdRegistryClient registry = mock(HouseholdRegistryClient.class);
    private final MemberMessenger messenger = mock(MemberMessenger.class);
    private RequestsReminders sut;

    private static MemberRequest request(final long id, final String member, final String text, final String author,
                                         final String doneBy) {
        return MemberRequest.builder().id(id).member(member).text(text).author(author).doneBy(doneBy).build();
    }

    @BeforeEach
    void setUp() {
        when(registry.members()).thenReturn(Mono.just(List.of(
            new Member("Liwia", "+48500000001", true, "resident"),
            new Member("Borys", "+48500000002", true, "resident"),
            new Member("Ewa", null, false, "resident"))));
        when(requests.mark(anyString(), any())).thenAnswer(call -> Mono.just((long) ((List<?>) call.getArgument(1)).size()));
        when(messenger.deliver(any(), any())).thenReturn(Mono.just(MemberMessenger.Delivery.PUSH));
        final RequestsProperties properties = new RequestsProperties(true, "0 0 8 * * *", "0 0 20 * * *",
            Duration.ofSeconds(60), Duration.ofDays(3), HOUSE);
        sut = new RequestsReminders(requests, registry, messenger, properties, EVENING);
    }

    @Test
    void should_tell_each_author_once_and_mark_what_was_delivered() {
        when(requests.doneToAnnounce()).thenReturn(Mono.just(List.of(
            request(1, "Liwia", "chleb", "Borys", "Liwia"),
            request(2, "Tobiasz", "mleko", "borys", "Tobiasz"),
            request(3, "Borys", "paczka", "Liwia", "Borys"))));

        sut.announceDone().as(StepVerifier::create).expectNext(new RequestsReminders.Outcome(2, 3)).verifyComplete();

        final ArgumentCaptor<PersonalMessage> toBorys = ArgumentCaptor.forClass(PersonalMessage.class);
        verify(messenger).deliver(eq(new Member("Borys", "+48500000002", true, "resident")), toBorys.capture());
        assertThat(toBorys.getValue().sms()).isEqualTo("Zrobione: chleb (Liwia), mleko (Tobiasz)");
        verify(requests).mark("done-announced", List.of(1L, 2L));
        verify(requests).mark("done-announced", List.of(3L));
    }

    //an author switched off or unknown is never told: marked, or the request would come back every minute
    @Test
    void should_mark_an_announcement_nobody_can_be_told() {
        when(requests.doneToAnnounce()).thenReturn(Mono.just(List.of(
            request(1, "Liwia", "chleb", "Ewa", "Liwia"),
            request(2, "Liwia", "mleko", "Nobody", "Liwia"))));

        sut.announceDone().as(StepVerifier::create).expectNext(new RequestsReminders.Outcome(0, 2)).verifyComplete();

        verify(messenger, never()).deliver(any(), any());
        verify(requests).mark("done-announced", List.of(1L));
        verify(requests).mark("done-announced", List.of(2L));
    }

    //no browser and no phone marks it too; a message that failed is not marked and goes again
    @Test
    void should_mark_an_unreachable_author_and_leave_a_failed_message() {
        when(requests.doneToAnnounce()).thenReturn(Mono.just(List.of(
            request(1, "Liwia", "chleb", "Borys", "Liwia"),
            request(2, "Borys", "mleko", "Liwia", "Borys"))));
        when(messenger.deliver(eq(new Member("Borys", "+48500000002", true, "resident")), any()))
            .thenReturn(Mono.just(MemberMessenger.Delivery.UNREACHABLE));
        when(messenger.deliver(eq(new Member("Liwia", "+48500000001", true, "resident")), any()))
            .thenReturn(Mono.just(MemberMessenger.Delivery.FAILED));

        sut.announceDone().as(StepVerifier::create).expectNext(new RequestsReminders.Outcome(0, 1)).verifyComplete();

        verify(requests).mark("done-announced", List.of(1L));
        verify(requests, never()).mark("done-announced", List.of(2L));
    }

    @Test
    void should_announce_nothing_and_ask_nobody_when_nothing_was_done() {
        when(requests.doneToAnnounce()).thenReturn(Mono.just(List.of()));

        sut.announceDone().as(StepVerifier::create).expectNext(RequestsReminders.Outcome.NOTHING).verifyComplete();
        verify(registry, never()).members();
    }

    //the evening: reminders of today and the requests texted three days ago - each to its own addressee
    @Test
    void should_remind_the_recipients_and_tell_the_authors_of_the_requests_still_open() {
        when(requests.toRemind(LocalDateTime.of(2026, 11, 10, 0, 0))).thenReturn(Mono.just(List.of(
            request(1, "Liwia", "chleb", "Borys", null),
            request(2, "Liwia", "mleko", "Liwia", null))));
        when(requests.stale(LocalDateTime.of(2026, 11, 7, 20, 0))).thenReturn(Mono.just(List.of(
            request(3, "Liwia", "paczka", "Borys", null))));

        sut.evening().as(StepVerifier::create).expectNext(new RequestsReminders.Outcome(2, 3)).verifyComplete();

        final ArgumentCaptor<PersonalMessage> toLiwia = ArgumentCaptor.forClass(PersonalMessage.class);
        verify(messenger).deliver(eq(new Member("Liwia", "+48500000001", true, "resident")), toLiwia.capture());
        assertThat(toLiwia.getValue().sms()).isEqualTo("Czy zrobione? Odhacz w aplikacji: chleb (Borys), mleko");
        assertThat(toLiwia.getValue().sticky()).isTrue();
        verify(requests).mark("reminded", List.of(1L, 2L));
        verify(requests).mark("stale-announced", List.of(3L));
    }

    //a reminder nobody can get is not marked - tomorrow is another; the announcement still is
    @Test
    void should_never_mark_a_reminder_that_could_not_go() {
        when(requests.toRemind(any())).thenReturn(Mono.just(List.of(request(1, "Ewa", "chleb", "Borys", null))));
        when(requests.stale(any())).thenReturn(Mono.just(List.of(request(2, "Liwia", "paczka", "Ewa", null))));

        sut.evening().as(StepVerifier::create).expectNext(new RequestsReminders.Outcome(0, 1)).verifyComplete();

        verify(requests, never()).mark(eq("reminded"), any());
        verify(requests).mark("stale-announced", List.of(2L));
    }

    //a request written for oneself is reminded every evening: still open, it is marked and its author not told twice
    @Test
    void should_mark_an_own_request_still_open_without_a_word() {
        when(requests.toRemind(any())).thenReturn(Mono.just(List.of()));
        when(requests.stale(any())).thenReturn(Mono.just(List.of(
            request(1, "Liwia", "chleb", "liwia", null),
            request(2, "Liwia", "paczka", "Borys", null))));

        sut.evening().as(StepVerifier::create).expectNext(new RequestsReminders.Outcome(1, 2)).verifyComplete();

        verify(requests).mark("stale-announced", List.of(1L));
        verify(requests).mark("stale-announced", List.of(2L));
        verify(messenger).deliver(eq(new Member("Borys", "+48500000002", true, "resident")), any());
        verify(messenger, never()).deliver(eq(new Member("Liwia", "+48500000001", true, "resident")), any());
    }

    //the reminders failing never keep the authors from being told, nor the other way round
    @Test
    void should_tell_the_authors_when_the_reminders_failed() {
        when(requests.toRemind(any())).thenReturn(Mono.error(new IllegalStateException("database-service answered 503")));
        when(requests.stale(any())).thenReturn(Mono.just(List.of(request(2, "Liwia", "paczka", "Borys", null))));

        sut.evening().as(StepVerifier::create).expectNext(new RequestsReminders.Outcome(1, 1)).verifyComplete();
    }

    @Test
    void should_never_fail() {
        when(requests.doneToAnnounce()).thenReturn(Mono.error(new IllegalStateException("database-service answered 503")));
        doReturn(Mono.error(new IllegalStateException("database-service answered 500"))).when(requests).mark(anyString(), any());

        sut.announceDone().as(StepVerifier::create).expectNext(RequestsReminders.Outcome.NOTHING).verifyComplete();

        //delivered, not marked: it counts, and goes again
        when(requests.doneToAnnounce()).thenReturn(Mono.just(List.of(request(1, "Liwia", "chleb", "Borys", "Liwia"))));
        sut.announceDone().as(StepVerifier::create).expectNext(new RequestsReminders.Outcome(1, 0)).verifyComplete();
    }

    //the manual trigger during the pass of the minute does nothing - both would tell the same authors
    @Test
    void should_run_one_pass_at_a_time() {
        final Sinks.One<List<MemberRequest>> held = Sinks.one();
        when(requests.doneToAnnounce()).thenReturn(held.asMono());

        final StepVerifier first = sut.announceDone().as(StepVerifier::create)
            .expectNext(RequestsReminders.Outcome.NOTHING).expectComplete().verifyLater();
        sut.announceDone().as(StepVerifier::create).expectNext(RequestsReminders.Outcome.NOTHING).verifyComplete();
        held.tryEmitValue(List.of());
        first.verify();

        verify(requests).doneToAnnounce();
    }
}
