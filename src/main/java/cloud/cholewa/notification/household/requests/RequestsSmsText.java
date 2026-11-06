package cloud.cholewa.notification.household.requests;

import cloud.cholewa.home.model.MemberRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The words of the SMS of the household requests (HAS-410), in Polish:
 * {@code Zapotrzebowanie na dziś: papier toaletowy (Liwia), żel pod prysznic (Borys)} - each request followed
 * by its author in brackets only when the author is not the recipient. The final wording is the owner's call
 * on a real SMS.
 * <p>
 * Not cut to 160 code points like {@code SmsText}: a list in Polish is Unicode, 70 characters a part, so the
 * text is kept whole up to {@link #MAX_CODE_POINTS} (3 parts); the requests that do not fit are replaced by
 * {@code i jeszcze N} - they stay pending and go out the next day. One request always fits: 100 of text, 50
 * of an author and the words around them are less.
 */
final class RequestsSmsText {

    static final int MAX_CODE_POINTS = 201;
    static final String HEADER = "Zapotrzebowanie na dziś: ";

    private RequestsSmsText() {
    }

    /** The text, and the requests it carries - only those may be marked sent. */
    record Composed(String text, List<MemberRequest> carried) {
    }

    static Composed of(final String recipient, final List<MemberRequest> requests) {
        for (int count = requests.size(); count > 1; count--) {
            final String text = textOf(recipient, requests, count);
            if (text.codePointCount(0, text.length()) <= MAX_CODE_POINTS) {
                return new Composed(text, List.copyOf(requests.subList(0, count)));
            }
        }
        return new Composed(textOf(recipient, requests, 1), List.copyOf(requests.subList(0, 1)));
    }

    private static String textOf(final String recipient, final List<MemberRequest> requests, final int count) {
        final List<String> items = new ArrayList<>();
        for (MemberRequest request : requests.subList(0, count)) {
            items.add(itemOf(recipient, request));
        }
        final int left = requests.size() - count;
        if (left > 0) {
            items.add("i jeszcze " + left);
        }
        return HEADER + String.join(", ", items);
    }

    private static String itemOf(final String recipient, final MemberRequest request) {
        final String author = request.getAuthor();
        final boolean own = author == null || author.toLowerCase(Locale.ROOT).equals(recipient.toLowerCase(Locale.ROOT));
        return own ? request.getText() : request.getText() + " (" + author + ")";
    }
}
