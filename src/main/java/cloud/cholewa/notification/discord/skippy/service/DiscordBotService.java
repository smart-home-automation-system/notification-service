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

    //Discord refuses a message whose content is longer
    static final int MAX_CONTENT_LENGTH = 2000;
    private static final String CUT_MARKER = "…";

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

    //the text as plain content, the level as a badge under it: an embed with nothing but a title
    //and the colored bar. The text stays out of the embed on purpose - a message made of an embed
    //alone has no preview in a push notification, and without the "Embed Links" permission
    //Discord refuses it as empty, while this one still arrives, only without its badge
    private MessageCreateRequest toRequest(final NotificationLevel level, final String message) {
        return MessageCreateRequest.builder()
            .content(fitContent(message))
            .addEmbed(EmbedData.builder()
                .title(level.name())
                .color(level.getColor())
                .build())
            .build();
    }

    //Discord answers a longer content with 400, and a 400 is not retried. Cut by code points:
    //half of an emoji at the end would be refused just the same
    private String fitContent(final String message) {
        if (message.length() <= MAX_CONTENT_LENGTH) {
            return message;
        }
        final int end = message.offsetByCodePoints(
            0, message.codePointCount(0, MAX_CONTENT_LENGTH - CUT_MARKER.length() - 1));

        log.warn("Message of {} characters cut to fit Discord's limit of {}", message.length(), MAX_CONTENT_LENGTH);
        return message.substring(0, end) + CUT_MARKER;
    }
}
