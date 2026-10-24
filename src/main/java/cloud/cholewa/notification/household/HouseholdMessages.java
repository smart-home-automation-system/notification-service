package cloud.cholewa.notification.household;

import cloud.cholewa.home.model.HouseholdNotification;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.time.format.DateTimeFormatter;

/**
 * The Polish words of the household notifications (HAS-274). The technical channel stays in
 * English; this one is read by the people who live in the house. The texts are the owner's call,
 * made on a real message (the lesson of HAS-94) - change them here, and the test that writes them
 * out. The subject is the name of the device as the registry has it, used as it comes. The switch
 * names every event: one added to the SDK stops the compiler here until it has its words.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class HouseholdMessages {

    //what needs somebody now, what still waits, what is done
    static final int FINISHED_COLOR = 0xF39C12;
    static final int REMINDER_COLOR = 0xE67E22;
    static final int ACKNOWLEDGED_COLOR = 0x2ECC71;

    private static final DateTimeFormatter TIME_OF_THE_HOUSE = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    public static HouseholdMessage of(final HouseholdNotification notification) {
        final String subject = notification.getSubject();
        final String footer = notification.getOccurredAt().format(TIME_OF_THE_HOUSE);

        return switch (notification.getEvent()) {
            //no verb that has a gender: the device name may be Polish of any gender, or English
            case APPLIANCE_FINISHED -> new HouseholdMessage(
                subject, "Koniec pracy: " + subject + " - wymaga opróżnienia.", footer, FINISHED_COLOR);
            //the payload carries the number of the reminder, not how many there will be
            case APPLIANCE_REMINDER -> new HouseholdMessage(
                subject, "Przypomnienie " + notification.getReminder() + ": " + subject + " nadal czeka na opróżnienie.",
                footer, REMINDER_COLOR);
            case APPLIANCE_ACKNOWLEDGED -> new HouseholdMessage(
                subject, subject + " - potwierdzone, dziękujemy.", footer, ACKNOWLEDGED_COLOR);
        };
    }
}
