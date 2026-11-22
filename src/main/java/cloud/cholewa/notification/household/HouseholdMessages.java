package cloud.cholewa.notification.household;

import cloud.cholewa.home.model.GateOpenSource;
import cloud.cholewa.home.model.HouseholdEvent;
import cloud.cholewa.home.model.HouseholdNotification;
import cloud.cholewa.home.model.RoomName;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.ZoneId;
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
 *
 * <p>The intercom (HAS-451, smart-home-sdk 1.22.0, published by gate-service): a ring at the gate and an opening of
 * the gate, told by what they are - "Domofon", "Brama" - not by the registry names of the station and the Shelly.
 * An opening says where it came from: the indoor screen, or the application by the member it names.
 *
 * <p>A garage gate left open (HAS-472, smart-home-sdk 1.23.0, published by gate-service - HAS-475): told on the
 * intercom channel as "Brama garażowa" and the registry name of the gate ({@code Kate}), how long it stands open from
 * {@code openSince}, the temperature of the cold alarm, the number of a repeat. The two alarms (cold, empty house) in
 * red, the warning (open long) in amber, the end in green.
 *
 * <p>A fire alarm (HAS-462, smart-home-sdk 1.24.0, published by safety-service - HAS-461): the one message nobody may
 * miss - texted to every member with a phone and pushed to every member, at home or not. Told by the detector (the
 * three kinds of the registry by their Polish names, any other name as it comes) and the room of the payload in Polish
 * after a colon - the nominative, no declension, no gender to agree with. The alarm in red, the end in green.
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
    //the intercom: a ring asks somebody to come, an opening is what happened
    static final int DOORBELL_COLOR = 0x3498DB;
    static final int GATE_OPENED_COLOR = 0x2ECC71;
    static final String DOORBELL_TITLE = "Domofon";
    static final String GATE_TITLE = "Brama";
    //a garage gate left open (HAS-472): an alarm asks somebody to close it, the warning only says so, closed is done
    static final int GARAGE_ALARM_COLOR = 0xE74C3C;
    static final int GARAGE_WARNING_COLOR = 0xF39C12;
    static final int GARAGE_CLOSED_COLOR = 0x2ECC71;
    static final String GARAGE_TITLE = "Brama garażowa";
    //a fire alarm (HAS-462): the alarm needs everybody now, the end is good news
    static final int FIRE_ALARM_COLOR = 0xE74C3C;
    static final int FIRE_ALARM_OVER_COLOR = 0x2ECC71;
    static final String FIRE_ALARM_TITLE = "Alarm pożarowy";
    static final String FIRE_ALARM_OVER_TITLE = "Koniec alarmu pożarowego";
    private static final Locale POLISH = Locale.forLanguageTag("pl");
    //the clock the publishers stamp occurredAt and openSince with
    private static final ZoneId HOUSE = ZoneId.of("Europe/Warsaw");

    //the GIOS index: above these (ug/m3) the air is very bad (level 6)
    static final int PM10_VERY_BAD = 150;
    static final int PM25_VERY_BAD = 110;

    //the registry name, whatever the case, to the Polish one; a registry renamed to Polish reads the same
    private static final Map<String, String> POLISH_NAMES = Map.of(
        "washing machine", "Pralka",
        "dryer", "Suszarka",
        "dishwasher", "Zmywarka");

    //the detectors of the registry are named by their kind in English (HAS-458: "Smoke detector")
    private static final Map<String, String> DETECTOR_NAMES = Map.of(
        "smoke detector", "Czujka dymu",
        "co detector", "Czujka czadu",
        "heat detector", "Czujka temperatury");

    private static final DateTimeFormatter TIME_OF_THE_HOUSE = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    public static HouseholdMessage of(final HouseholdNotification notification) {
        final String footer = notification.getOccurredAt().format(TIME_OF_THE_HOUSE);
        if (isSmog(notification)) {
            return smog(notification, footer);
        }
        if (isIntercom(notification)) {
            return intercom(notification, footer);
        }
        if (isFire(notification)) {
            return fire(notification, footer);
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
            case DOORBELL_RINGING, GATE_OPENED, GARAGE_GATE_OPEN_COLD, GARAGE_GATE_OPEN_EMPTY_HOUSE, GARAGE_GATE_OPEN_LONG,
                 GARAGE_GATE_CLOSED -> intercom(notification, footer);
            case FIRE_ALARM, FIRE_ALARM_OVER -> fire(notification, footer);
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
            case DOORBELL_RINGING, GATE_OPENED, GARAGE_GATE_OPEN_COLD, GARAGE_GATE_OPEN_EMPTY_HOUSE, GARAGE_GATE_OPEN_LONG,
                 GARAGE_GATE_CLOSED -> intercom(notification, footer);
            case FIRE_ALARM, FIRE_ALARM_OVER -> fire(notification, footer);
        };
    }

    //the events of the intercom (HAS-451) and of the garage gates (HAS-472) - on the intercom channel of Discord
    public static boolean isIntercom(final HouseholdNotification notification) {
        return notification.getEvent() == HouseholdEvent.DOORBELL_RINGING
            || notification.getEvent() == HouseholdEvent.GATE_OPENED
            || isGarageGate(notification);
    }

    //a garage gate left open, or closed after it (HAS-472)
    public static boolean isGarageGate(final HouseholdNotification notification) {
        return notification.getEvent() == HouseholdEvent.GARAGE_GATE_OPEN_COLD
            || notification.getEvent() == HouseholdEvent.GARAGE_GATE_OPEN_EMPTY_HOUSE
            || notification.getEvent() == HouseholdEvent.GARAGE_GATE_OPEN_LONG
            || notification.getEvent() == HouseholdEvent.GARAGE_GATE_CLOSED;
    }

    //the two alarms of a garage gate (HAS-472) - a gate open while it is cold, or while nobody is at home
    public static boolean isGarageAlarm(final HouseholdNotification notification) {
        return notification.getEvent() == HouseholdEvent.GARAGE_GATE_OPEN_COLD
            || notification.getEvent() == HouseholdEvent.GARAGE_GATE_OPEN_EMPTY_HOUSE;
    }

    /**
     * Whether a message goes by SMS: never the smog (HAS-430) nor the doorbell and the gate (HAS-451); of a garage
     * gate only the first message of an alarm (HAS-472, owner 2026-11-18 - the warning, a repeat and the end never) -
     * and that one to the members named for it, at home or not. Both events of a fire alarm are texted, to every member
     * with a phone at home or not (HAS-462).
     */
    public static boolean isTexted(final HouseholdNotification notification) {
        if (isGarageAlarm(notification)) {
            return Integer.valueOf(0).equals(notification.getReminder());
        }
        return !isSmog(notification) && !isIntercom(notification);
    }

    //the start and the end of an alarm of a fire detector (HAS-462)
    public static boolean isFire(final HouseholdNotification notification) {
        return notification.getEvent() == HouseholdEvent.FIRE_ALARM
            || notification.getEvent() == HouseholdEvent.FIRE_ALARM_OVER;
    }

    //"Czujka dymu w pomieszczeniu: kuchnia - alarm!" - the room after a colon needs no case, the detector no gender
    private static HouseholdMessage fire(final HouseholdNotification notification, final String footer) {
        final String detector = Optional.ofNullable(notification.getSubject())
            .map(String::strip)
            .filter(name -> !name.isEmpty())
            .map(name -> DETECTOR_NAMES.getOrDefault(name.toLowerCase(Locale.ROOT), name))
            .orElse("Czujka");
        final String where = Optional.ofNullable(notification.getRoom())
            .map(room -> detector + " w pomieszczeniu: " + roomInPolish(room))
            .orElse(detector);
        if (notification.getEvent() == HouseholdEvent.FIRE_ALARM_OVER) {
            return new HouseholdMessage(FIRE_ALARM_OVER_TITLE, where + " - alarm ustał.", footer, FIRE_ALARM_OVER_COLOR);
        }
        return new HouseholdMessage(FIRE_ALARM_TITLE,
            where + " - alarm! Sprawdź natychmiast. Jeśli się pali, wyjdź z domu i dzwoń pod 112.", footer,
            FIRE_ALARM_COLOR);
    }

    //the rooms of the house in Polish, the nominative: a room added to the SDK stops the compiler here
    static String roomInPolish(final RoomName room) {
        return switch (room) {
            case LOFT -> "strych";
            case WARDROBE -> "garderoba";
            case BEDROOM -> "sypialnia";
            case LIVIA -> "pokój Livii";
            case TOBI -> "pokój Tobiego";
            case OFFICE -> "gabinet";
            case BATHROOM_UP -> "łazienka na piętrze";
            case HALL_UP -> "korytarz na piętrze";
            case STAIRS -> "schody";
            case KITCHEN -> "kuchnia";
            case LIVING_ROOM -> "salon";
            case CINEMA -> "kino";
            case HALL_DOWN -> "korytarz na parterze";
            case BATHROOM_DOWN -> "łazienka na parterze";
            case ENTRANCE -> "wiatrołap";
            case GARAGE -> "garaż";
            case BOILER -> "kotłownia";
            case GARDEN -> "ogród";
            case SAUNA -> "sauna";
            case SANCTUM -> "sanktuarium";
        };
    }

    private static HouseholdMessage intercom(final HouseholdNotification notification, final String footer) {
        if (isGarageGate(notification)) {
            return garageGate(notification, footer);
        }
        if (notification.getEvent() == HouseholdEvent.DOORBELL_RINGING) {
            return new HouseholdMessage(DOORBELL_TITLE, "Ktoś dzwoni do bramy.", footer, DOORBELL_COLOR);
        }
        return new HouseholdMessage(GATE_TITLE, gateOpened(notification), footer, GATE_OPENED_COLOR);
    }

    //"Brama garażowa Kate": the registry name after the noun - nominative, no gender to agree with
    private static HouseholdMessage garageGate(final HouseholdNotification notification, final String footer) {
        final String title = Optional.ofNullable(notification.getSubject())
            .map(String::strip)
            .filter(name -> !name.isEmpty())
            .map(name -> GARAGE_TITLE + " " + name)
            .orElse(GARAGE_TITLE);
        final String open = openFor(notification).map(time -> "Otwarta od " + time).orElse("Otwarta");
        return switch (notification.getEvent()) {
            case GARAGE_GATE_OPEN_COLD -> new HouseholdMessage(title,
                open + temperature(notification) + " - zamknij ją." + repeat(notification), footer, GARAGE_ALARM_COLOR);
            case GARAGE_GATE_OPEN_EMPTY_HOUSE -> new HouseholdMessage(title,
                open + ", a w domu nikogo nie ma - zamknij ją." + repeat(notification), footer, GARAGE_ALARM_COLOR);
            case GARAGE_GATE_OPEN_LONG -> new HouseholdMessage(title,
                open + "." + repeat(notification), footer, GARAGE_WARNING_COLOR);
            default -> new HouseholdMessage(title,
                openFor(notification).map(time -> "Zamknięta po " + time + ".").orElse("Zamknięta."), footer,
                GARAGE_CLOSED_COLOR);
        };
    }

    //how long it stands open: from openSince to the moment of the message, both in the time of the house - compared as
    //instants of its zone, so the night the clocks change counts the hour that was or was not
    private static Optional<String> openFor(final HouseholdNotification notification) {
        return Optional.ofNullable(notification.getOpenSince())
            .map(since -> Duration.between(since.atZone(HOUSE), notification.getOccurredAt().atZone(HOUSE)))
            .filter(open -> !open.isNegative())
            .map(open -> minutesAndHours(open.toMinutes()));
    }

    //"15 min", "2 h", "1 h 15 min"
    private static String minutesAndHours(final long minutes) {
        if (minutes < 60) {
            return minutes + " min";
        }
        final long rest = minutes % 60;
        final String hours = minutes / 60 + " h";
        return rest == 0 ? hours : hours + " " + rest + " min";
    }

    //", a na zewnątrz 7,5 °C" - the reading that made it an alarm, in the decimal comma of the house
    private static String temperature(final HouseholdNotification notification) {
        return Optional.ofNullable(notification.getTemperature())
            .map(degrees -> String.format(POLISH, ", a na zewnątrz %.1f °C", degrees))
            .orElse(", a na zewnątrz jest zimno");
    }

    //the payload counts the repeats: 0 is the first message (required - the reader refuses a payload without it)
    private static String repeat(final HouseholdNotification notification) {
        final int reminder = notification.getReminder();
        return reminder == 0 ? "" : " (przypomnienie " + reminder + ")";
    }

    //from the screen, from the application by a member (the name after the verb, no case), from the entrance panel at the
    //door (HAS-481), or from where not known
    private static String gateOpened(final HouseholdNotification notification) {
        final Optional<String> member = Optional.ofNullable(notification.getMember())
            .map(String::strip)
            .filter(name -> !name.isEmpty());
        if (notification.getSource() == GateOpenSource.SCREEN) {
            return "Brama otwarta z ekranu domofonu.";
        }
        if (notification.getSource() == GateOpenSource.DASHBOARD) {
            return member.map(name -> "Brama otwarta z aplikacji - otwiera " + name + ".")
                .orElse("Brama otwarta z aplikacji.");
        }
        if (notification.getSource() == GateOpenSource.PANEL) {
            return "Brama otwarta z panelu w wejściu.";
        }
        return "Brama otwarta.";
    }
}
