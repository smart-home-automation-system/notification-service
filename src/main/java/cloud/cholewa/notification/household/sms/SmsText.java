package cloud.cholewa.notification.household.sms;

import cloud.cholewa.notification.household.HouseholdMessage;
import cloud.cholewa.notification.model.NotificationLevel;

/**
 * The text of a household SMS (HAS-275): the sentence of the Discord message, cut to {@link #MAX_LENGTH}
 * characters where longer - by code points, so that no character is cut in half. The Polish letters make it
 * a Unicode SMS (70 characters a part), so a long sentence goes in up to three parts; the sentences of
 * {@code HouseholdMessages} stay well below that.
 */
final class SmsText {

    static final int MAX_LENGTH = 160;
    private static final String CUT = "…";
    private static final String GSM_CUT = "...";

    private SmsText() {
    }

    static String of(final HouseholdMessage message) {
        return cut(message.text().strip(), CUT);
    }

    /**
     * The SMS of a marked alert or info (HAS-335): the level as the Discord embed titles it, then the message -
     * {@code ERROR: The grid is lost}. Only characters of the basic GSM alphabet are added - no brackets (an
     * escape each, two of 160) and the cut marked with three dots, not {@code …} (outside GSM, it would make the
     * SMS Unicode, 70 characters a part): a message of GSM characters stays one part, cut or not. One character
     * outside GSM in the message itself ({@code °}, a Polish letter) makes it Unicode, up to three parts.
     */
    static String of(final NotificationLevel level, final String message) {
        return cut(level.name() + ": " + message.strip(), GSM_CUT);
    }

    private static String cut(final String text, final String marker) {
        if (text.codePointCount(0, text.length()) <= MAX_LENGTH) {
            return text;
        }
        final int end = text.offsetByCodePoints(0, MAX_LENGTH - marker.length());
        return text.substring(0, end) + marker;
    }
}
