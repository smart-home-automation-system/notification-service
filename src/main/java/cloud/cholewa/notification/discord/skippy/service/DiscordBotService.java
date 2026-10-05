package cloud.cholewa.notification.discord.skippy.service;

import cloud.cholewa.notification.infrastructure.error.NotificationException;
import discord4j.common.util.Snowflake;
import discord4j.core.DiscordClient;
import discord4j.core.object.entity.channel.Channel;
import discord4j.discordjson.json.ChannelData;
import discord4j.rest.http.client.ClientException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@RequiredArgsConstructor
public class DiscordBotService {

    private static final String ALERTS_CHANNEL = "alerts";
    private static final int NOT_FOUND = 404;

    private final DiscordClient skippy;

    //looked up once and kept: without it every message, and every retry of it, would list the
    //guilds and their channels again before posting
    private final AtomicReference<Snowflake> alertsChannelId = new AtomicReference<>();

    //over the REST API only: skippy.login() opens a gateway session, which nothing here ever
    //closed - one more websocket for every message sent
    public Mono<Void> sendMessage(final String message) {
        return alertsChannel()
            .flatMap(channelId -> skippy.getChannelById(channelId).createMessage(message)
                //the channel was deleted or re-created: forget it, the next attempt looks it up again
                .doOnError(ClientException.isStatusCode(NOT_FOUND), throwable -> alertsChannelId.set(null)))
            .doOnNext(sent -> log.info("Message sent to Discord channel: {}", ALERTS_CHANNEL))
            //discord4j retries a 5xx itself and, when that runs out, signals Reactor's "retries
            //exhausted" with the answer only as its cause; callers decide by the answer
            .onErrorMap(
                throwable -> Exceptions.isRetryExhausted(throwable)
                    && throwable.getCause() instanceof ClientException,
                Throwable::getCause)
            .then();
    }

    private Mono<Snowflake> alertsChannel() {
        return Mono.defer(() -> {
            final Snowflake known = alertsChannelId.get();

            return known != null ? Mono.just(known) : findAlertsChannel();
        });
    }

    //the first match only: one message, one post - posting to every match could not be retried
    //without repeating the posts that had already succeeded
    private Mono<Snowflake> findAlertsChannel() {
        return skippy.getGuilds()
            .concatMap(guild -> skippy.getGuildById(Snowflake.of(guild.id())).getChannels())
            .filter(DiscordBotService::isAlertsChannel)
            .next()
            .map(channel -> Snowflake.of(channel.id()))
            .doOnNext(alertsChannelId::set)
            //no channel is a failure, not a success: the caller would otherwise report a
            //notification as delivered that nobody will ever read
            .switchIfEmpty(Mono.error(() ->
                new NotificationException("Discord text channel not found: " + ALERTS_CHANNEL)));
    }

    private static boolean isAlertsChannel(final ChannelData channel) {
        return channel.type() == Channel.Type.GUILD_TEXT.getValue()
            && channel.name().toOptional().filter(ALERTS_CHANNEL::equalsIgnoreCase).isPresent();
    }
}
