package cloud.cholewa.notification.discord.skippy.api;

import cloud.cholewa.home.model.GateOpenSource;
import cloud.cholewa.home.model.HouseholdEvent;
import cloud.cholewa.home.model.HouseholdNotification;
import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.notification.discord.skippy.service.DiscordBotService;
import cloud.cholewa.notification.household.HouseholdMessages;
import cloud.cholewa.notification.household.sms.MarkedNotificationSms;
import cloud.cholewa.notification.household.sms.SmsSmoke;
import cloud.cholewa.notification.model.NotificationLevel;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * Sends a message by hand - the smoke test through a port-forward; deliberately not routed by the
 * gateway. Two mappings of one path: a text to the alerts channel, as always, and - with
 * {@code category=household} (HAS-274) - a household notification of a made-up device, worded as
 * the household queue words one and posted to the household channel. Apart, so that the
 * parameters of one are neither read nor checked on a call of the other. Discord only, never the
 * push of the household; the SMS only with {@code sms=true}, to the administrators alone (HAS-275) - on
 * both mappings since HAS-335. {@code event=smog} (HAS-430) is a sample {@code smog warning} of the air quality
 * sensor with its particles - Discord only, never texted: with {@code sms=true} it is refused. {@code event=doorbell}
 * (HAS-451) is a sample {@code doorbell ringing} of the station at the gate, posted to the intercom channel - as is any
 * event of the intercom ({@code doorbell ringing}, {@code gate opened} - with {@code source=} screen, dashboard or panel,
 * HAS-481); never texted either. {@code event=fire alarm}
 * and {@code event=fire alarm over} (HAS-462) are a sample alarm and its end of a smoke detector of the kitchen - with
 * {@code sms=true} texted to the administrators alone, never to every member as the real alarm is.
 * {@code event=load shedding started}, {@code load shedding device running} and {@code load shedding over} (HAS-522) are
 * samples of the SOLAX inverter with two loads cut, one still at work and three switched back on; the end is never
 * texted, so with {@code sms=true} it is refused.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("skippy")
public class DiscordBotController {

    static final String SMOG_SAMPLE = "smog";
    static final String DOORBELL_SAMPLE = "doorbell";
    static final String GARAGE_SAMPLE = "garage";
    //the values of the two events themselves: the sample gives them a detector and a room
    static final String FIRE_SAMPLE = "fire alarm";
    static final String FIRE_OVER_SAMPLE = "fire alarm over";
    //the values of the three events of load shedding (HAS-522): the sample gives them the inverter and two loads
    static final String LOAD_SHEDDING_STARTED_SAMPLE = "load shedding started";
    static final String LOAD_SHEDDING_DEVICE_RUNNING_SAMPLE = "load shedding device running";
    static final String LOAD_SHEDDING_OVER_SAMPLE = "load shedding over";
    //the device of the default sample, and a load of the samples of load shedding
    private static final String DISHWASHER = "Zmywarka";

    private final DiscordBotService service;
    private final ObjectProvider<SmsSmoke> smsSmoke;
    private final ObjectProvider<MarkedNotificationSms> markedNotificationSms;

    //with sms=true (HAS-335) the path of a marked alert: the Discord post, then the same text by SMS to the
    //administrators - refused while the SMS is switched off
    @GetMapping
    Mono<ResponseEntity<Void>> sendMessage(
        @RequestParam final String message,
        @RequestParam(required = false) final String level,
        @RequestParam(defaultValue = "false") final boolean sms
    ) {
        final NotificationLevel notificationLevel = NotificationLevel.of(level, NotificationLevel.INFO);
        final Mono<MarkedNotificationSms> texting = Mono.just(sms)
            .filter(Boolean::booleanValue)
            .flatMap(asked -> Mono.justOrEmpty(markedNotificationSms.getIfAvailable())
                .switchIfEmpty(Mono.error(() ->
                    new ResponseStatusException(HttpStatus.CONFLICT, "the SMS is switched off"))))
            .cache();
        //as a marked message on the queue: the SMS also when the post failed, and the failure still answered
        final Mono<Void> text = texting.flatMap(markedSms -> markedSms.send(notificationLevel, message));
        return texting
            .then(Mono.defer(() -> service.sendMessage(notificationLevel, message)))
            .onErrorResume(
                failure -> !(failure instanceof ResponseStatusException),
                failure -> text.then(Mono.error(failure)))
            .then(text)
            .thenReturn(ResponseEntity.ok().build());
    }

    @GetMapping(params = "category=household")
    Mono<ResponseEntity<Void>> sendHouseholdMessage(
        @RequestParam(defaultValue = "appliance finished") final String event,
        @RequestParam(required = false) @Size(min = 3, max = 50) final String subject,
        @RequestParam(defaultValue = "0") @Min(0) @Max(10) final int reminder,
        @RequestParam(required = false) @Size(min = 3, max = 50) final String member,
        @RequestParam(required = false) final String source,
        @RequestParam(defaultValue = "false") final boolean sms
    ) {
        //with sms=true (HAS-275) also one SMS to the administrators of the household - asked for by name,
        //never by default, and refused while the SMS is switched off
        final Mono<SmsSmoke> texting = Mono.just(sms)
            .filter(Boolean::booleanValue)
            .flatMap(asked -> Mono.justOrEmpty(smsSmoke.getIfAvailable())
                .switchIfEmpty(Mono.error(() ->
                    new ResponseStatusException(HttpStatus.CONFLICT, "the SMS of the household is switched off"))))
            .cache();
        //deferred: an unknown event is a signal, and the smog and the intercom are never texted
        return Mono.fromCallable(() -> sampleOf(event, subject, reminder, member, source))
            .filter(notification -> !sms || !neverTexted(notification))
            .switchIfEmpty(Mono.error(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "the " + event + " is never texted")))
            .flatMap(notification -> texting
                .then(Mono.fromCallable(() -> HouseholdMessages.of(notification)))
                .flatMap(message -> service.sendHouseholdMessage(notification, message)
                    .then(texting.flatMap(smoke -> smoke.sendToAdministrators(notification, message)).then())))
            .thenReturn(ResponseEntity.ok().build());
    }

    //what the real path never texts, and the samples of the intercom channel - the garage sample too, whose alarm the
    //real path texts to the members named for it, never to the administrators of a smoke test
    private static boolean neverTexted(final HouseholdNotification notification) {
        return !HouseholdMessages.isTexted(notification) || HouseholdMessages.isIntercom(notification);
    }

    private static HouseholdNotification sampleOf(final String event, final String subject, final int reminder,
                                                  final String member, final String source) {
        return switch (event) {
            case SMOG_SAMPLE -> smogSample(subject);
            case DOORBELL_SAMPLE -> doorbellSample(subject);
            case GARAGE_SAMPLE -> garageSample(subject);
            case FIRE_SAMPLE -> fireSample(HouseholdEvent.FIRE_ALARM, subject);
            case FIRE_OVER_SAMPLE -> fireSample(HouseholdEvent.FIRE_ALARM_OVER, subject);
            case LOAD_SHEDDING_STARTED_SAMPLE -> loadSheddingSample(HouseholdEvent.LOAD_SHEDDING_STARTED, subject,
                List.of("Czajnik", "Pralka"));
            case LOAD_SHEDDING_DEVICE_RUNNING_SAMPLE -> loadSheddingSample(HouseholdEvent.LOAD_SHEDDING_DEVICE_RUNNING,
                subject, List.of(DISHWASHER));
            case LOAD_SHEDDING_OVER_SAMPLE -> loadSheddingSample(HouseholdEvent.LOAD_SHEDDING_OVER, subject,
                List.of("Czajnik", "Pralka", DISHWASHER));
            default -> testNotification(event, subject, reminder, member, source);
        };
    }

    //a sample event of load shedding of the SOLAX inverter with the loads it is about (HAS-522)
    private static HouseholdNotification loadSheddingSample(final HouseholdEvent event, final String subject,
                                                           final List<String> devices) {
        return HouseholdNotification.builder()
            .event(event)
            .subject(subject == null ? "SOLAX" : subject)
            .deviceId(1L)
            .occurredAt(LocalDateTime.now(ZoneId.systemDefault()))
            .reminder(0)
            .devices(devices)
            .build();
    }

    //a sample alarm, or its end, of a smoke detector of the kitchen (HAS-462)
    private static HouseholdNotification fireSample(final HouseholdEvent event, final String subject) {
        return HouseholdNotification.builder()
            .event(event)
            .subject(subject == null ? "Smoke detector" : subject)
            .deviceId(1L)
            .occurredAt(LocalDateTime.now(ZoneId.systemDefault()))
            .reminder(0)
            .room(RoomName.KITCHEN)
            .build();
    }

    //a sample cold alarm of a garage gate (HAS-472): open for 15 minutes below 15 C outside
    private static HouseholdNotification garageSample(final String subject) {
        final LocalDateTime now = LocalDateTime.now(ZoneId.systemDefault());
        return HouseholdNotification.builder()
            .event(HouseholdEvent.GARAGE_GATE_OPEN_COLD)
            .subject(subject == null ? "Test" : subject)
            .deviceId(1L)
            .occurredAt(now)
            .reminder(0)
            .openSince(now.minusMinutes(15))
            .temperature(7.5)
            .build();
    }

    //a sample smog warning of the air quality sensor - bad, not very bad
    private static HouseholdNotification smogSample(final String subject) {
        return HouseholdNotification.builder()
            .event(HouseholdEvent.SMOG_WARNING)
            .subject(subject == null ? "Air quality sensor" : subject)
            .deviceId(1L)
            .occurredAt(LocalDateTime.now(ZoneId.systemDefault()))
            .reminder(0)
            .pm10(112)
            .pm25(78)
            .build();
    }

    //a sample ring of the station at the gate
    private static HouseholdNotification doorbellSample(final String subject) {
        return HouseholdNotification.builder()
            .event(HouseholdEvent.DOORBELL_RINGING)
            .subject(subject == null ? "Intercom" : subject)
            .deviceId(1L)
            .occurredAt(LocalDateTime.now(ZoneId.systemDefault()))
            .reminder(0)
            .build();
    }

    private static HouseholdNotification testNotification(final String event, final String subject, final int reminder,
                                                         final String member, final String source) {
        final HouseholdEvent householdEvent;
        try {
            householdEvent = HouseholdEvent.fromValue(event);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown household event: " + event);
        }
        return HouseholdNotification.builder()
            .event(householdEvent)
            .subject(subject == null ? DISHWASHER : subject)
            .deviceId(1L)
            .occurredAt(LocalDateTime.now(ZoneId.systemDefault()))
            .reminder(reminder)
            .member(member)
            .source(sourceOf(source))
            .build();
    }

    //where a sample gate opened came from (HAS-481): screen, dashboard or panel - none when not asked
    private static GateOpenSource sourceOf(final String source) {
        if (source == null) {
            return null;
        }
        try {
            return GateOpenSource.fromValue(source);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown source of an opening: " + source);
        }
    }
}
