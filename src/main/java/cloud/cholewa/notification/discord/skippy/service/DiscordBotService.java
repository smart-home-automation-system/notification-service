package cloud.cholewa.notification.discord.skippy.service;

import discord4j.core.DiscordClient;
import discord4j.core.object.entity.Guild;
import discord4j.core.object.entity.channel.TextChannel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
@Slf4j
public class DiscordBotService {

    private final DiscordClient skippy;

    public Mono<ResponseEntity<Void>> sendMessage(String message) {
        log.info("Sending a message to Discord Bot with token");

        return skippy.login()
            .flatMap(client -> client.getGuilds()
                .flatMap(Guild::getChannels)
                .filter(guildChannel -> guildChannel instanceof TextChannel)
                .cast(TextChannel.class)
                .filter(textChannel -> textChannel.getName().equalsIgnoreCase("family"))
                .flatMap(textChannel -> textChannel.createMessage(message))
                .then(Mono.empty())
            );
    }
}
