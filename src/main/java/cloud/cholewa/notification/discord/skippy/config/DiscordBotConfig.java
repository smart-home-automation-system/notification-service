package cloud.cholewa.notification.discord.skippy.config;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("discord.bot.skippy")
@AllArgsConstructor
@Getter
public class DiscordBotConfig {
    private String token;
}
