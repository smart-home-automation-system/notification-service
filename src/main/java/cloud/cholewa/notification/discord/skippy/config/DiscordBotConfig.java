package cloud.cholewa.notification.discord.skippy.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The Discord ids of the text channels (in Discord: developer mode, "Copy Channel ID"); neither
 * is a secret. {@code alertsChannelId} - every alert and info of the system, in English.
 * {@code householdChannelId} - the household category (HAS-274), in Polish, for the people who
 * live in the house. {@code intercomChannelId} - the doorbell and the gate (HAS-451), in Polish, a channel of its
 * own (owner, 2026-11-16).
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

    @NotBlank
    @Pattern(regexp = "\\d+", message = "must be a numeric Discord channel id")
    private String householdChannelId;

    @NotBlank
    @Pattern(regexp = "\\d+", message = "must be a numeric Discord channel id")
    private String intercomChannelId;
}
