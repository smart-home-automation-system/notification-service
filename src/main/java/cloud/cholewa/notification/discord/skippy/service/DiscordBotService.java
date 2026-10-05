package cloud.cholewa.notification.discord.skippy.service;

import cloud.cholewa.notification.discord.skippy.config.DiscordBotConfig;
import discord4j.common.util.Snowflake;
import discord4j.core.DiscordClient;
import discord4j.rest.http.client.ClientException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;

@Slf4j
@Service
public class DiscordBotService {

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
    public Mono<Void> sendMessage(final String message) {
        return skippy.getChannelById(alertsChannelId).createMessage(message)
            .doOnNext(sent -> log.info("Message sent to Discord channel: {}", alertsChannelId.asString()))
            //discord4j retries a 5xx itself and, when that runs out, signals Reactor's "retries
            //exhausted" with the answer only as its cause; callers decide by the answer
            .onErrorMap(
                throwable -> Exceptions.isRetryExhausted(throwable)
                    && throwable.getCause() instanceof ClientException,
                Throwable::getCause)
            .then();
    }
}
