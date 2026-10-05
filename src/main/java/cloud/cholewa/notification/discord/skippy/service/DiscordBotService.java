package cloud.cholewa.notification.discord.skippy.service;

import cloud.cholewa.notification.discord.skippy.config.DiscordBotConfig;
import cloud.cholewa.notification.model.NotificationLevel;
import discord4j.common.util.Snowflake;
import discord4j.core.DiscordClient;
import discord4j.discordjson.json.EmbedData;
import discord4j.discordjson.json.MessageCreateRequest;
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
    private static final String CUT_MARKER = "…";
    static final String EMPTY_MESSAGE = "(empty message)";

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
        return skippy.getChannelById(alertsChannelId).createMessage(toRequest(level, message))
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

    //one embed and nothing else: the level is its title, the text its description, the bar on its
    //left the color of the level. 0.4.0 kept the text outside, as plain content with a bare
    //"ERROR" box under it, and the owner found that hard to read. What the embed-only message
    //costs: it needs the bot's "Embed Links" permission on the channel (it has it), and a push
    //notification may show less of it than of plain content
    private MessageCreateRequest toRequest(final NotificationLevel level, final String message) {
        return MessageCreateRequest.builder()
            .addEmbed(EmbedData.builder()
                .title(level.name())
                .description(fitDescription(message))
                .color(level.getColor())
                .build())
            .build();
    }

    //Discord answers a longer description with 400, and a 400 is not retried. Cut by code points:
    //half of an emoji at the end would be refused just the same
    private String fitDescription(final String message) {
        //a title alone would say that something happened and not what
        if (message.isBlank()) {
            return EMPTY_MESSAGE;
        }
        if (message.length() <= MAX_DESCRIPTION_LENGTH) {
            return message;
        }
        final int end = message.offsetByCodePoints(
            0, message.codePointCount(0, MAX_DESCRIPTION_LENGTH - CUT_MARKER.length() - 1));

        log.warn(
            "Message of {} characters cut to fit Discord's limit of {}", message.length(), MAX_DESCRIPTION_LENGTH);
        return message.substring(0, end) + CUT_MARKER;
    }
}
