package cloud.cholewa.notification.discord.skippy.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code alertsChannelId} - the Discord id of the text channel every notification is posted on
 * (in Discord: developer mode, "Copy Channel ID"). It is not a secret.
 */
@Getter
@Validated
@AllArgsConstructor
@ConfigurationProperties("discord.bot.skippy")
public class DiscordBotConfig {

    private String token;

    @NotBlank
    @Pattern(regexp = "\\d+", message = "must be a numeric Discord channel id")
    private String alertsChannelId;
}
