package cloud.cholewa.notification.household.sms;

import cloud.cholewa.notification.household.HouseholdMessage;

/**
 * The text of a household SMS (HAS-275): the sentence of the Discord message, cut to {@link #MAX_LENGTH}
 * characters where longer - by code points, so that no character is cut in half. The Polish letters make it
 * a Unicode SMS (70 characters a part), so a long sentence goes in up to three parts; the sentences of
 * {@code HouseholdMessages} stay well below that.
 */
final class SmsText {

    static final int MAX_LENGTH = 160;
    private static final String CUT = "…";

    private SmsText() {
    }

    static String of(final HouseholdMessage message) {
        final String text = message.text().strip();
        if (text.codePointCount(0, text.length()) <= MAX_LENGTH) {
            return text;
        }
        final int end = text.offsetByCodePoints(0, MAX_LENGTH - 1);
        return text.substring(0, end) + CUT;
    }
}
