package cloud.cholewa.notification.household;

import cloud.cholewa.home.model.HouseholdNotification;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The Polish words of the household notifications (HAS-274). The technical channel stays in
 * English; this one is read by the people who live in the house. The texts are the owner's call,
 * made on a real message (the lesson of HAS-94) - change them here, and the test that writes them
 * out. The switch names every event: one added to the SDK stops the compiler here until it has its
 * words.
 *
 * <p>The registry names the appliances in English (HAS-285, owner 2026-10-26): the washing machine,
 * the dryer and the dishwasher are told by their Polish names - all three feminine, so the sentences
 * may say what the appliance did. A device not in {@link #POLISH_NAMES} keeps its registry name in
 * sentences without a gendered verb.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class HouseholdMessages {

    //what needs somebody now, what still waits, what is done
    static final int FINISHED_COLOR = 0xF39C12;
    static final int REMINDER_COLOR = 0xE67E22;
    static final int ACKNOWLEDGED_COLOR = 0x2ECC71;

    //the registry name, whatever the case, to the Polish one; a registry renamed to Polish reads the same
    private static final Map<String, String> POLISH_NAMES = Map.of(
        "washing machine", "Pralka",
        "dryer", "Suszarka",
        "dishwasher", "Zmywarka");

    private static final DateTimeFormatter TIME_OF_THE_HOUSE = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    public static HouseholdMessage of(final HouseholdNotification notification) {
        final String footer = notification.getOccurredAt().format(TIME_OF_THE_HOUSE);
        return polishNameOf(notification.getSubject())
            .map(name -> named(notification, name, footer))
            .orElseGet(() -> unnamed(notification, footer));
    }

    private static Optional<String> polishNameOf(final String subject) {
        return Optional.ofNullable(subject)
            .map(name -> name.strip().toLowerCase(Locale.ROOT))
            .flatMap(key -> Optional.ofNullable(POLISH_NAMES.get(key))
                .or(() -> POLISH_NAMES.values().stream().filter(polish -> polish.toLowerCase(Locale.ROOT).equals(key)).findFirst()));
    }

    //a known appliance: a feminine noun
    private static HouseholdMessage named(final HouseholdNotification notification, final String name,
                                          final String footer) {
        return switch (notification.getEvent()) {
            case APPLIANCE_FINISHED -> new HouseholdMessage(
                name, name + " skończyła pracę - czas ją opróżnić.", footer, FINISHED_COLOR);
            //the payload carries the number of the reminder, not how many there will be
            case APPLIANCE_REMINDER -> new HouseholdMessage(
                name, name + " wciąż czeka na opróżnienie (przypomnienie " + notification.getReminder() + ").",
                footer, REMINDER_COLOR);
            case APPLIANCE_ACKNOWLEDGED -> new HouseholdMessage(
                name, acknowledgedBy(notification, name + " opróżniona")
                    .orElse(name + " opróżniona - dziękujemy!"), footer, ACKNOWLEDGED_COLOR);
        };
    }

    //who acknowledged it (HAS-347): the name stands after the verb, so it needs neither a case nor a gender
    private static Optional<String> acknowledgedBy(final HouseholdNotification notification, final String start) {
        return Optional.ofNullable(notification.getMember())
            .map(String::strip)
            .filter(member -> !member.isEmpty())
            .map(member -> start + " - potwierdza " + member + ". Dziękujemy!");
    }

    //any other device: no verb that has a gender - its name may be Polish of any gender, or English
    private static HouseholdMessage unnamed(final HouseholdNotification notification, final String footer) {
        final String subject = notification.getSubject();
        return switch (notification.getEvent()) {
            case APPLIANCE_FINISHED -> new HouseholdMessage(
                subject, "Koniec pracy: " + subject + " - wymaga opróżnienia.", footer, FINISHED_COLOR);
            case APPLIANCE_REMINDER -> new HouseholdMessage(
                subject, "Przypomnienie " + notification.getReminder() + ": " + subject + " nadal czeka na opróżnienie.",
                footer, REMINDER_COLOR);
            case APPLIANCE_ACKNOWLEDGED -> new HouseholdMessage(
                subject, acknowledgedBy(notification, subject)
                    .orElse(subject + " - potwierdzone, dziękujemy."), footer, ACKNOWLEDGED_COLOR);
        };
    }
}
