package cloud.cholewa.notification.household;

import cloud.cholewa.home.model.HouseholdNotification;
import cloud.cholewa.notification.discord.skippy.service.DiscordBotService;
import cloud.cholewa.notification.service.DiscordDeliveryRetry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

//the household channel on Discord - always, whoever is at home (the owner's decision of 2026-10-11)
@Component
@RequiredArgsConstructor
public class DiscordHouseholdTransport implements HouseholdTransport {

    private final DiscordBotService discordBotService;

    @Override
    public String name() {
        return "discord";
    }

    @Override
    public Mono<Void> deliver(final HouseholdNotification notification, final HouseholdMessage message) {
        return discordBotService.sendHouseholdMessage(message)
            .retryWhen(DiscordDeliveryRetry.spec());
    }
}
