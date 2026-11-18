package cloud.cholewa.notification.discord.skippy.api;

import cloud.cholewa.home.model.HouseholdEvent;
import cloud.cholewa.home.model.HouseholdNotification;
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
 * event of the intercom ({@code doorbell ringing}, {@code gate opened}); never texted either.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("skippy")
public class DiscordBotController {

    static final String SMOG_SAMPLE = "smog";
    static final String DOORBELL_SAMPLE = "doorbell";

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
        return Mono.fromCallable(() -> sampleOf(event, subject, reminder, member))
            .filter(notification -> !sms || !neverTexted(notification))
            .switchIfEmpty(Mono.error(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "the " + event + " is never texted")))
            .flatMap(notification -> texting
                .then(Mono.fromCallable(() -> HouseholdMessages.of(notification)))
                .flatMap(message -> service.sendHouseholdMessage(notification, message)
                    .then(texting.flatMap(smoke -> smoke.sendToAdministrators(message)).then())))
            .thenReturn(ResponseEntity.ok().build());
    }

    private static boolean neverTexted(final HouseholdNotification notification) {
        return HouseholdMessages.isSmog(notification) || HouseholdMessages.isIntercom(notification);
    }

    private static HouseholdNotification sampleOf(final String event, final String subject, final int reminder,
                                                  final String member) {
        return switch (event) {
            case SMOG_SAMPLE -> smogSample(subject);
            case DOORBELL_SAMPLE -> doorbellSample(subject);
            default -> testNotification(event, subject, reminder, member);
        };
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
                                                         final String member) {
        final HouseholdEvent householdEvent;
        try {
            householdEvent = HouseholdEvent.fromValue(event);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown household event: " + event);
        }
        return HouseholdNotification.builder()
            .event(householdEvent)
            .subject(subject == null ? "Zmywarka" : subject)
            .deviceId(1L)
            .occurredAt(LocalDateTime.now(ZoneId.systemDefault()))
            .reminder(reminder)
            .member(member)
            .build();
    }
}
