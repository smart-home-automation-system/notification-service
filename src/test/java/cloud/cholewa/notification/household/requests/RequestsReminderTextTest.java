package cloud.cholewa.notification.household.requests;

import cloud.cholewa.home.model.MemberRequest;
import cloud.cholewa.notification.household.PersonalMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

//The words of the reminders and the announcements of the household requests (HAS-416), written out: the person
//in brackets, so no name is declined and no verb takes a gender
class RequestsReminderTextTest {

    private static MemberRequest request(final long id, final String member, final String text, final String author,
                                         final String doneBy) {
        return MemberRequest.builder().id(id).member(member).text(text).author(author).doneBy(doneBy).build();
    }

    @Test
    void should_tell_the_author_who_did_each_request() {
        final RequestsReminderText.Composed composed = RequestsReminderText.done(List.of(
            request(1, "Liwia", "chleb", "Borys", "Liwia"),
            request(2, "Tobiasz", "mleko", "Borys", "Tobiasz")));

        assertThat(composed.message()).isEqualTo(new PersonalMessage("Zrobione", "chleb (Liwia), mleko (Tobiasz)",
            "Zrobione: chleb (Liwia), mleko (Tobiasz)", "requests-done", "/room", false));
        assertThat(composed.carried()).extracting(MemberRequest::getId).containsExactly(1L, 2L);
    }

    //the author in brackets only when it is somebody else, as in the morning SMS; it asks, so it stays on the screen
    @Test
    void should_remind_the_recipient_of_the_open_requests() {
        final RequestsReminderText.Composed composed = RequestsReminderText.reminder("Liwia", List.of(
            request(1, "Liwia", "chleb", "Borys", null),
            request(2, "Liwia", "mleko", "liwia", null)));

        assertThat(composed.message()).isEqualTo(new PersonalMessage("Czy zrobione?", "chleb (Borys), mleko",
            "Czy zrobione? Odhacz w aplikacji: chleb (Borys), mleko", "requests-reminder", "/room", true));
    }

    @Test
    void should_tell_the_author_who_still_has_a_request_open() {
        final RequestsReminderText.Composed composed = RequestsReminderText.stale(List.of(
            request(1, "Liwia", "chleb", "Borys", null),
            request(2, null, "mleko", "Borys", null)));

        assertThat(composed.message()).isEqualTo(new PersonalMessage("Wciąż otwarte", "chleb (Liwia), mleko",
            "Wciąż otwarte: chleb (Liwia), mleko", "requests-stale", "/room", false));
    }

    //a long list as the morning SMS: what fits, the rest counted - only what it carries is marked
    @Test
    void should_carry_what_fits_and_count_the_rest() {
        final List<MemberRequest> many = IntStream.rangeClosed(1, 8)
            .mapToObj(n -> request(n, "Liwia", "papier toaletowy i ręczniki " + n, "Borys", "Liwia"))
            .toList();

        final RequestsReminderText.Composed composed = RequestsReminderText.done(many);

        assertThat(composed.message().sms().codePointCount(0, composed.message().sms().length()))
            .isLessThanOrEqualTo(RequestsSmsText.MAX_CODE_POINTS);
        assertThat(composed.message().sms()).endsWith("i jeszcze " + (8 - composed.carried().size()));
        assertThat(composed.carried()).hasSizeBetween(1, 7);
    }
}
