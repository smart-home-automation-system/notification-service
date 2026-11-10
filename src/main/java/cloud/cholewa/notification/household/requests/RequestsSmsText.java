package cloud.cholewa.notification.household.requests;

import cloud.cholewa.home.model.MemberRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

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
        final Fitted fitted = fit(HEADER, requests.stream().map(request -> itemOf(recipient, request)).toList());
        return new Composed(HEADER + fitted.list(), List.copyOf(requests.subList(0, fitted.count())));
    }

    /** A list after its header, and how many of its items it carries; shared with the reminders (HAS-416). */
    record Fitted(String list, int count) {
    }

    //the most items that fit MAX_CODE_POINTS with the header, the rest as "i jeszcze N" - one always
    static Fitted fit(final String header, final List<String> items) {
        for (int count = items.size(); count > 1; count--) {
            final String text = header + listOf(items, count);
            if (text.codePointCount(0, text.length()) <= MAX_CODE_POINTS) {
                return new Fitted(listOf(items, count), count);
            }
        }
        return new Fitted(listOf(items, 1), 1);
    }

    private static String listOf(final List<String> items, final int count) {
        final List<String> shown = new ArrayList<>(items.subList(0, count));
        final int left = items.size() - count;
        if (left > 0) {
            shown.add("i jeszcze " + left);
        }
        return String.join(", ", shown);
    }

    static String itemOf(final String recipient, final MemberRequest request) {
        //an author the answer leaves out is no other member: nothing in brackets
        final String author = Objects.toString(request.getAuthor(), "");
        final boolean own = author.isEmpty() || author.toLowerCase(Locale.ROOT).equals(recipient.toLowerCase(Locale.ROOT));
        return own ? request.getText() : request.getText() + " (" + author + ")";
    }
}
