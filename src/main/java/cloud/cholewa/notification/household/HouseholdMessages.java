package cloud.cholewa.notification.household;

import cloud.cholewa.home.model.HouseholdEvent;
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
 *
 * <p>The smog outside the house (HAS-430, smart-home-sdk 1.21.0, published by weather-service): the subject is the
 * air quality sensor, the particles the reading of the event. The words say how bad it is by the thresholds of the
 * GIOS index - the payload carries the values only: above {@link #PM10_VERY_BAD} of PM10 or {@link #PM25_VERY_BAD}
 * of PM2.5 "bardzo zły" in red, otherwise "zły" in amber. A message without the numbers is still worded.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class HouseholdMessages {

    //what needs somebody now, what still waits, what is done
    static final int FINISHED_COLOR = 0xF39C12;
    static final int REMINDER_COLOR = 0xE67E22;
    static final int ACKNOWLEDGED_COLOR = 0x2ECC71;
    //the smog: bad in amber, very bad in red; over in the colour of the acknowledgement
    static final int SMOG_BAD_COLOR = 0xF39C12;
    static final int SMOG_VERY_BAD_COLOR = 0xE74C3C;

    //the GIOS index: above these (ug/m3) the air is very bad (level 6)
    static final int PM10_VERY_BAD = 150;
    static final int PM25_VERY_BAD = 110;

    //the registry name, whatever the case, to the Polish one; a registry renamed to Polish reads the same
    private static final Map<String, String> POLISH_NAMES = Map.of(
        "washing machine", "Pralka",
        "dryer", "Suszarka",
        "dishwasher", "Zmywarka");

    private static final DateTimeFormatter TIME_OF_THE_HOUSE = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    public static HouseholdMessage of(final HouseholdNotification notification) {
        final String footer = notification.getOccurredAt().format(TIME_OF_THE_HOUSE);
        if (isSmog(notification)) {
            return smog(notification, footer);
        }
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
            case SMOG_WARNING, SMOG_OVER -> smog(notification, footer);
        };
    }

    //the events of the smog (HAS-430) - never texted (owner, 2026-11-12: Discord and Web Push only)
    public static boolean isSmog(final HouseholdNotification notification) {
        return notification.getEvent() == HouseholdEvent.SMOG_WARNING || notification.getEvent() == HouseholdEvent.SMOG_OVER;
    }

    private static HouseholdMessage smog(final HouseholdNotification notification, final String footer) {
        final Integer pm10 = notification.getPm10();
        final Integer pm25 = notification.getPm25();
        if (notification.getEvent() == HouseholdEvent.SMOG_OVER) {
            final String text = pm10 == null
                ? "Powietrze na zewnątrz znów w normie."
                : "Powietrze na zewnątrz znów w normie (PM10 " + pm10 + " µg/m³).";
            return new HouseholdMessage(notification.getSubject(), text, footer, ACKNOWLEDGED_COLOR);
        }
        final boolean veryBad = (pm10 != null && pm10 > PM10_VERY_BAD) || (pm25 != null && pm25 > PM25_VERY_BAD);
        final String particles = particles(pm10, pm25);
        final String text = "Smog na zewnątrz" + (particles.isEmpty() ? "" : ": " + particles)
            + " - poziom " + (veryBad ? "bardzo zły" : "zły") + ". Zamknij okna i nie wietrz.";
        return new HouseholdMessage(notification.getSubject(), text, footer, veryBad ? SMOG_VERY_BAD_COLOR : SMOG_BAD_COLOR);
    }

    //"PM10 112 µg/m³, PM2.5 78 µg/m³" - what the message carries of the two
    private static String particles(final Integer pm10, final Integer pm25) {
        final String ten = pm10 == null ? "" : "PM10 " + pm10 + " µg/m³";
        final String twoAndHalf = pm25 == null ? "" : "PM2.5 " + pm25 + " µg/m³";
        return ten.isEmpty() || twoAndHalf.isEmpty() ? ten + twoAndHalf : ten + ", " + twoAndHalf;
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
            case SMOG_WARNING, SMOG_OVER -> smog(notification, footer);
        };
    }
}
