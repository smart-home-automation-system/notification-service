package cloud.cholewa.notification.household.requests;

import cloud.cholewa.home.model.MemberRequest;
import cloud.cholewa.notification.household.PersonalMessage;

import java.util.List;
import java.util.Objects;

/**
 * The words of the reminders and the announcements of the household requests (HAS-416), in Polish - the owner's
 * call on a real message. Each a list as the morning SMS has it ({@link RequestsSmsText#fit}: up to 201 code
 * points, the rest {@code i jeszcze N}, only what the text carries marked), the person named in brackets, so no
 * name is declined and no verb takes a gender:
 * <ul>
 *   <li>to the author, a request ticked off: {@code Zrobione: chleb (Liwia)};</li>
 *   <li>to the recipient at 20:00: {@code Czy zrobione? Odhacz w aplikacji: chleb, mleko (Borys)} - the author in
 *   brackets when it is somebody else, as in the morning;</li>
 *   <li>to the author, a request open some days after its SMS: {@code Wciąż otwarte: chleb (Liwia)}.</li>
 * </ul>
 * The push shows the title and the list, the SMS the same in one line. A tap opens My room, where the requests
 * are ticked off; each kind has a tag of its own, so a newer message replaces the older one of the same kind.
 */
final class RequestsReminderText {

    static final String MY_ROOM = "/room";
    static final String DONE_HEADER = "Zrobione: ";
    static final String REMINDER_HEADER = "Czy zrobione? Odhacz w aplikacji: ";
    static final String STALE_HEADER = "Wciąż otwarte: ";

    private RequestsReminderText() {
    }

    /** The message, and the requests it carries - only those may be marked. */
    record Composed(PersonalMessage message, List<MemberRequest> carried) {
    }

    static Composed done(final List<MemberRequest> requests) {
        return composed("Zrobione", DONE_HEADER, "requests-done", false, requests,
            requests.stream().map(request -> named(request, request.getDoneBy())).toList());
    }

    static Composed reminder(final String recipient, final List<MemberRequest> requests) {
        return composed("Czy zrobione?", REMINDER_HEADER, "requests-reminder", true, requests,
            requests.stream().map(request -> RequestsSmsText.itemOf(recipient, request)).toList());
    }

    static Composed stale(final List<MemberRequest> requests) {
        return composed("Wciąż otwarte", STALE_HEADER, "requests-stale", false, requests,
            requests.stream().map(request -> named(request, request.getMember())).toList());
    }

    private static Composed composed(final String title, final String header, final String tag, final boolean sticky,
                                     final List<MemberRequest> requests, final List<String> items) {
        final RequestsSmsText.Fitted fitted = RequestsSmsText.fit(header, items);
        final PersonalMessage message = new PersonalMessage(title, fitted.list(), header + fitted.list(), tag, MY_ROOM,
            sticky);
        return new Composed(message, List.copyOf(requests.subList(0, fitted.count())));
    }

    //a person the answer leaves out is named by nobody: no brackets
    private static String named(final MemberRequest request, final String person) {
        final String name = Objects.toString(person, "");
        return name.isBlank() ? request.getText() : request.getText() + " (" + name + ")";
    }
}
