package cloud.cholewa.notification.discord.skippy.service;

import cloud.cholewa.notification.discord.skippy.config.DiscordBotConfig;
import cloud.cholewa.notification.household.HouseholdMessage;
import cloud.cholewa.notification.model.NotificationLevel;
import discord4j.common.util.Snowflake;
import discord4j.core.DiscordClient;
import discord4j.discordjson.json.EmbedData;
import discord4j.discordjson.json.EmbedFooterData;
import discord4j.discordjson.json.MessageCreateRequest;
import discord4j.rest.http.client.ClientException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;

import java.util.function.Supplier;

@Slf4j
@Service
public class DiscordBotService {

    //Discord refuses an embed whose description is longer
    static final int MAX_DESCRIPTION_LENGTH = 4096;
    //and one whose title is longer
    static final int MAX_TITLE_LENGTH = 256;
    private static final String CUT_MARKER = "…";
    static final String EMPTY_MESSAGE = "(empty message)";

    private final DiscordClient skippy;
    private final Snowflake alertsChannelId;
    private final Snowflake householdChannelId;

    public DiscordBotService(final DiscordClient skippy, final DiscordBotConfig config) {
        this.skippy = skippy;
        this.alertsChannelId = Snowflake.of(config.getAlertsChannelId());
        this.householdChannelId = Snowflake.of(config.getHouseholdChannelId());
    }

    //straight to the configured channel, over the REST API only. Not by login(): that opens a
    //gateway session nothing here ever closed. And not by looking the channel up by name: that
    //makes discord4j decode every channel of the server, and in 0.3.0 one channel it could not
    //decode took the whole delivery down with it
    public Mono<Void> sendMessage(final NotificationLevel level, final String message) {
        return post(alertsChannelId, level.name(), () -> toRequest(level, message));
    }

    //the household channel (HAS-274): the device as the title, the Polish text inside, the time of
    //the house in the footer, the bar colored by the event
    public Mono<Void> sendHouseholdMessage(final HouseholdMessage message) {
        return post(householdChannelId, "Household", () -> toRequest(message));
    }

    private Mono<Void> post(final Snowflake channel, final String what, final Supplier<MessageCreateRequest> request) {
        //deferred: the request is built before discord4j returns a Mono, and an exception thrown
        //there has to reach the caller as a signal - a listener that lets one escape gets its
        //message redelivered at once
        return Mono.defer(() -> skippy.getChannelById(channel).createMessage(request.get()))
            .doOnNext(sent -> log.info("{} message sent to Discord channel: {}", what, channel.asString()))
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

    private MessageCreateRequest toRequest(final HouseholdMessage message) {
        return MessageCreateRequest.builder()
            .addEmbed(EmbedData.builder()
                .title(fit(message.title(), MAX_TITLE_LENGTH))
                .description(fitDescription(message.text()))
                .color(message.color())
                .footer(EmbedFooterData.builder().text(message.footer()).build())
                .build())
            .build();
    }

    //Discord answers a longer description with 400, and a 400 is not retried
    private String fitDescription(final String message) {
        //a title alone would say that something happened and not what
        if (message.isBlank()) {
            return EMPTY_MESSAGE;
        }
        return fit(message, MAX_DESCRIPTION_LENGTH);
    }

    //cut by code points: half of an emoji at the end would be refused just the same
    private String fit(final String text, final int limit) {
        if (text.length() <= limit) {
            return text;
        }
        final int end = text.offsetByCodePoints(0, text.codePointCount(0, limit - CUT_MARKER.length() - 1));

        log.warn("Text of {} characters cut to fit Discord's limit of {}", text.length(), limit);
        return text.substring(0, end) + CUT_MARKER;
    }
}
