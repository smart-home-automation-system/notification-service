package cloud.cholewa.notification.discord.skippy.service;

import cloud.cholewa.notification.infrastructure.error.NotificationException;
import discord4j.common.util.Snowflake;
import discord4j.core.DiscordClient;
import discord4j.core.object.entity.channel.Channel;
import discord4j.discordjson.json.ChannelData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Slf4j
@Service
@RequiredArgsConstructor
public class DiscordBotService {

    private static final String ALERTS_CHANNEL = "alerts";

    private final DiscordClient skippy;

    //over the REST API only: skippy.login() opens a gateway session, which nothing here ever
    //closed - one more websocket for every message sent
    public Mono<Void> sendMessage(final String message) {
        return skippy.getGuilds()
            .flatMap(guild -> skippy.getGuildById(Snowflake.of(guild.id())).getChannels())
            .filter(DiscordBotService::isAlertsChannel)
            .flatMap(channel -> skippy.getChannelById(Snowflake.of(channel.id())).createMessage(message))
            .doOnNext(sent -> log.info("Message sent to Discord channel: {}", ALERTS_CHANNEL))
            //no channel is a failure, not a success: the caller would otherwise report a
            //notification as delivered that nobody will ever read
            .switchIfEmpty(Mono.error(() ->
                new NotificationException("Discord text channel not found: " + ALERTS_CHANNEL)))
            .then();
    }

    private static boolean isAlertsChannel(final ChannelData channel) {
        return channel.type() == Channel.Type.GUILD_TEXT.getValue()
            && channel.name().toOptional().filter(ALERTS_CHANNEL::equalsIgnoreCase).isPresent();
    }
}
