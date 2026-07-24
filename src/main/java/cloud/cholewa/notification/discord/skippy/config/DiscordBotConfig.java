package cloud.cholewa.notification.discord.skippy.config;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@AllArgsConstructor
@ConfigurationProperties("discord.bot.skippy")
public class DiscordBotConfig {
    private String token;
}
