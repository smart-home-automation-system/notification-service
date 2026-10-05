package cloud.cholewa.notification.discord.skippy.service;

import cloud.cholewa.notification.discord.skippy.config.DiscordBotConfig;
import cloud.cholewa.notification.model.NotificationLevel;
import discord4j.common.util.Snowflake;
import discord4j.core.DiscordClient;
import discord4j.discordjson.json.EmbedData;
import discord4j.rest.http.client.ClientException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;

@Slf4j
@Service
public class DiscordBotService {

    //Discord refuses an embed whose description is longer
    static final int MAX_DESCRIPTION_LENGTH = 4096;

    private final DiscordClient skippy;
    private final Snowflake alertsChannelId;

    public DiscordBotService(final DiscordClient skippy, final DiscordBotConfig config) {
        this.skippy = skippy;
        this.alertsChannelId = Snowflake.of(config.getAlertsChannelId());
    }

    //straight to the configured channel, over the REST API only. Not by login(): that opens a
    //gateway session nothing here ever closed. And not by looking the channel up by name: that
    //makes discord4j decode every channel of the server, and in 0.3.0 one channel it could not
    //decode took the whole delivery down with it
    public Mono<Void> sendMessage(final NotificationLevel level, final String message) {
        return skippy.getChannelById(alertsChannelId).createMessage(toEmbed(level, message))
            .doOnNext(sent -> log.info(
                "{} message sent to Discord channel: {}", level, alertsChannelId.asString()))
            //discord4j retries a 5xx itself and, when that runs out, signals Reactor's "retries
            //exhausted" with the answer only as its cause; callers decide by the answer
            .onErrorMap(
                throwable -> Exceptions.isRetryExhausted(throwable)
                    && throwable.getCause() instanceof ClientException,
                Throwable::getCause)
            .then();
    }

    //an embed, for the colored bar on its left: the level is the title, the text the description
    private static EmbedData toEmbed(final NotificationLevel level, final String message) {
        return EmbedData.builder()
            .title(level.name())
            .description(message.length() > MAX_DESCRIPTION_LENGTH
                ? message.substring(0, MAX_DESCRIPTION_LENGTH)
                : message)
            .color(level.getColor())
            .build();
    }
}
