package cloud.cholewa.notification.service;

import cloud.cholewa.notification.discord.skippy.service.DiscordBotService;
import cloud.cholewa.notification.model.NotificationLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class NotificationMessageService {

    private final DiscordBotService discordBotService;

    /**
     * Delivers an alert or an info to Discord, retried as {@link DiscordDeliveryRetry} says.
     */
    public Mono<Void> processMessage(final NotificationLevel level, final String message) {
        return discordBotService.sendMessage(level, message)
            .retryWhen(DiscordDeliveryRetry.spec());
    }
}
