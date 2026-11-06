package cloud.cholewa.notification.household.requests;

import cloud.cholewa.home.model.MemberRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

//The words of the SMS of the household requests (HAS-410), written out: the final wording is the owner's call
//on a real SMS
class RequestsSmsTextTest {

    private static MemberRequest request(final long id, final String text, final String author) {
        return MemberRequest.builder().id(id).member("Liwia").text(text).author(author).build();
    }

    @Test
    void should_word_one_request_of_another_member() {
        final RequestsSmsText.Composed composed = RequestsSmsText.of("Liwia",
            List.of(request(1, "papier toaletowy", "Borys")));

        assertThat(composed.text()).isEqualTo("Zapotrzebowanie na dziś: papier toaletowy (Borys)");
        assertThat(composed.carried()).extracting(MemberRequest::getId).containsExactly(1L);
    }

    //the author in brackets only when it is not the recipient, whatever the case
    @Test
    void should_word_two_requests_and_leave_out_the_recipient_as_author() {
        final RequestsSmsText.Composed composed = RequestsSmsText.of("Liwia",
            List.of(request(1, "papier toaletowy", "liwia"), request(2, "żel pod prysznic", "Borys")));

        assertThat(composed.text()).isEqualTo("Zapotrzebowanie na dziś: papier toaletowy, żel pod prysznic (Borys)");
        assertThat(composed.carried()).hasSize(2);
    }

    //fifteen requests do not fit three parts: the rest is "i jeszcze N", and only what the text carries is marked
    @Test
    void should_keep_fifteen_requests_to_three_parts_and_count_the_rest() {
        final List<MemberRequest> fifteen = LongStream.rangeClosed(1, 15)
            .mapToObj(id -> request(id, "rzecz numer " + id, "Borys"))
            .toList();

        final RequestsSmsText.Composed composed = RequestsSmsText.of("Liwia", fifteen);

        final int left = 15 - composed.carried().size();
        assertThat(composed.text().codePointCount(0, composed.text().length())).isLessThanOrEqualTo(201);
        assertThat(composed.text()).startsWith("Zapotrzebowanie na dziś: rzecz numer 1 (Borys), rzecz numer 2 (Borys)")
            .endsWith(", i jeszcze " + left);
        assertThat(left).isPositive();
        assertThat(composed.carried()).extracting(MemberRequest::getId)
            .containsExactlyElementsOf(LongStream.rangeClosed(1, composed.carried().size()).boxed().toList());
        //one more would not have fitted
        final String oneMore = RequestsSmsText.HEADER + String.join(", ", fifteen.subList(0, composed.carried().size() + 1)
            .stream().map(request -> request.getText() + " (Borys)").toList()) + ", i jeszcze " + (left - 1);
        assertThat(oneMore.codePointCount(0, oneMore.length())).isGreaterThan(201);
    }

    //the longest request there is fits alone: 100 of text and 50 of an author
    @Test
    void should_always_carry_the_first_request() {
        final RequestsSmsText.Composed composed = RequestsSmsText.of("Liwia", List.of(
            request(1, "ż".repeat(100), "B".repeat(50)), request(2, "ż".repeat(100), "B".repeat(50))));

        assertThat(composed.carried()).extracting(MemberRequest::getId).containsExactly(1L);
        assertThat(composed.text()).endsWith(", i jeszcze 1");
        assertThat(composed.text().codePointCount(0, composed.text().length())).isLessThanOrEqualTo(201);
    }
}
