package cloud.cholewa.notification.discord.skippy.service;

import discord4j.core.DiscordClient;
import discord4j.core.object.entity.Guild;
import discord4j.core.object.entity.channel.TextChannel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Slf4j
@Service
@RequiredArgsConstructor
public class DiscordBotService {

    private final DiscordClient skippy;

    public Mono<Void> sendMessage(final String message) {

        return skippy.login()
            .doOnNext(next -> log.info("Sending a message to Discord Bot with token"))
            .flatMap(client -> client.getGuilds()
                .flatMap(Guild::getChannels)
                .filter(TextChannel.class::isInstance)
                .cast(TextChannel.class)
                .filter(textChannel -> textChannel.getName().equalsIgnoreCase("alerts"))
                .flatMap(textChannel -> textChannel.createMessage(message))
                .then(Mono.empty())
            );
    }
}
