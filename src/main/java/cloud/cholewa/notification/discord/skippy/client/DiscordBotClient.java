package cloud.cholewa.notification.discord.skippy.client;

import cloud.cholewa.notification.discord.skippy.config.DiscordBotConfig;
import discord4j.core.DiscordClient;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DiscordBotClient {

    private final DiscordBotConfig config;

    @Bean
    public DiscordClient discordBotSkippy() {
        return DiscordClient.create(config.getToken());
    }
}
