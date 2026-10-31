package cloud.cholewa.notification.service;

import cloud.cholewa.notification.discord.skippy.service.DiscordBotService;
import cloud.cholewa.notification.household.sms.MarkedNotificationSms;
import cloud.cholewa.notification.model.NotificationLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationMessageService {

    private final DiscordBotService discordBotService;
    private final ObjectProvider<MarkedNotificationSms> markedNotificationSms;

    /**
     * Delivers an alert or an info to Discord, retried as {@link DiscordDeliveryRetry} says. A message its
     * publisher marked for an SMS (HAS-335) is texted to the administrators of the household afterwards - after
     * the Discord post, which the SMS never holds back, and also when Discord failed for good: that is when
     * the SMS matters most. The SMS never fails the delivery; the error of Discord is still signalled.
     */
    public Mono<Void> processMessage(final NotificationLevel level, final String message, final boolean sms) {
        final Mono<Void> texting = Mono.just(sms)
            .filter(Boolean::booleanValue)
            .flatMap(marked -> text(level, message));
        return discordBotService.sendMessage(level, message)
            .retryWhen(DiscordDeliveryRetry.spec())
            .onErrorResume(failure -> texting.then(Mono.error(failure)))
            .then(texting);
    }

    //the SMS switched off (household.sms.enabled) is no failure of a marked message: Discord has it
    private Mono<Void> text(final NotificationLevel level, final String message) {
        return Mono.defer(() -> Mono.justOrEmpty(markedNotificationSms.getIfAvailable()))
            .switchIfEmpty(Mono.fromRunnable(() -> log.warn(
                "The SMS is switched off: the marked message went to Discord only: {}", message)))
            .flatMap(markedSms -> markedSms.send(level, message));
    }
}
