package cloud.cholewa.notification.discord.skippy.api;

import cloud.cholewa.home.model.HouseholdEvent;
import cloud.cholewa.home.model.HouseholdNotification;
import cloud.cholewa.notification.discord.skippy.service.DiscordBotService;
import cloud.cholewa.notification.household.HouseholdMessages;
import cloud.cholewa.notification.model.NotificationLevel;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
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
 * SMS or the push of the household, which cost or wake somebody.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("skippy")
public class DiscordBotController {

    private final DiscordBotService service;

    @GetMapping
    Mono<ResponseEntity<Void>> sendMessage(
        @RequestParam final String message,
        @RequestParam(required = false) final String level
    ) {
        return service.sendMessage(NotificationLevel.of(level, NotificationLevel.INFO), message)
            .thenReturn(ResponseEntity.ok().build());
    }

    @GetMapping(params = "category=household")
    Mono<ResponseEntity<Void>> sendHouseholdMessage(
        @RequestParam(defaultValue = "appliance finished") final String event,
        @RequestParam(defaultValue = "Zmywarka") @Size(min = 3, max = 50) final String subject,
        @RequestParam(defaultValue = "0") @Min(0) @Max(10) final int reminder
    ) {
        return Mono.fromCallable(() -> HouseholdMessages.of(testNotification(event, subject, reminder)))
            .flatMap(service::sendHouseholdMessage)
            .thenReturn(ResponseEntity.ok().build());
    }

    private static HouseholdNotification testNotification(final String event, final String subject, final int reminder) {
        final HouseholdEvent householdEvent;
        try {
            householdEvent = HouseholdEvent.fromValue(event);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown household event: " + event);
        }
        return HouseholdNotification.builder()
            .event(householdEvent)
            .subject(subject)
            .deviceId(1L)
            .occurredAt(LocalDateTime.now(ZoneId.systemDefault()))
            .reminder(reminder)
            .build();
    }
}
