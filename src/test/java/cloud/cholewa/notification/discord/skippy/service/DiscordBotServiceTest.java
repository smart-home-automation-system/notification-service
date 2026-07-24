package cloud.cholewa.notification.discord.skippy.service;

import discord4j.core.DiscordClient;
import discord4j.core.GatewayDiscordClient;
import discord4j.core.object.entity.Guild;
import discord4j.core.object.entity.Message;
import discord4j.core.object.entity.channel.GuildChannel;
import discord4j.core.object.entity.channel.TextChannel;
import discord4j.core.object.entity.channel.VoiceChannel;
import discord4j.core.spec.MessageCreateMono;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DiscordBotServiceTest {

    private static final String MESSAGE = "test message";

    @Mock
    private DiscordClient skippy;

    @Mock
    private GatewayDiscordClient gatewayClient;

    @InjectMocks
    private DiscordBotService discordBotService;

    @Test
    void should_send_message_to_alerts_text_channel() {
        final TextChannel alerts = mock(TextChannel.class);
        final Guild guild = guildWith(alerts);

        when(skippy.login()).thenReturn(Mono.just(gatewayClient));
        when(gatewayClient.getGuilds()).thenReturn(Flux.just(guild));
        when(alerts.getName()).thenReturn("alerts");
        when(alerts.createMessage(MESSAGE)).thenReturn(messageMono());

        discordBotService.sendMessage(MESSAGE)
            .as(StepVerifier::create)
            .verifyComplete();

        verify(alerts).createMessage(MESSAGE);
    }

    @Test
    void should_match_alerts_channel_ignoring_case() {
        final TextChannel alerts = mock(TextChannel.class);
        final Guild guild = guildWith(alerts);

        when(skippy.login()).thenReturn(Mono.just(gatewayClient));
        when(gatewayClient.getGuilds()).thenReturn(Flux.just(guild));
        when(alerts.getName()).thenReturn("ALERTS");
        when(alerts.createMessage(MESSAGE)).thenReturn(messageMono());

        discordBotService.sendMessage(MESSAGE)
            .as(StepVerifier::create)
            .verifyComplete();

        verify(alerts).createMessage(MESSAGE);
    }

    @Test
    void should_not_send_message_to_non_alerts_text_channel() {
        final TextChannel general = mock(TextChannel.class);
        final Guild guild = guildWith(general);

        when(skippy.login()).thenReturn(Mono.just(gatewayClient));
        when(gatewayClient.getGuilds()).thenReturn(Flux.just(guild));
        when(general.getName()).thenReturn("general");

        discordBotService.sendMessage(MESSAGE)
            .as(StepVerifier::create)
            .verifyComplete();

        verify(general, never()).createMessage(MESSAGE);
    }

    @Test
    void should_ignore_non_text_channels() {
        final VoiceChannel voice = mock(VoiceChannel.class);
        final Guild guild = guildWith(voice);

        when(skippy.login()).thenReturn(Mono.just(gatewayClient));
        when(gatewayClient.getGuilds()).thenReturn(Flux.just(guild));

        discordBotService.sendMessage(MESSAGE)
            .as(StepVerifier::create)
            .verifyComplete();

        verifyNoInteractions(voice);
    }

    @Test
    void should_complete_empty_when_no_guilds_available() {
        when(skippy.login()).thenReturn(Mono.just(gatewayClient));
        when(gatewayClient.getGuilds()).thenReturn(Flux.empty());

        discordBotService.sendMessage(MESSAGE)
            .as(StepVerifier::create)
            .verifyComplete();
    }

    private static Guild guildWith(final GuildChannel channel) {
        final Guild guild = mock(Guild.class);
        when(guild.getChannels()).thenReturn(Flux.just(channel));
        return guild;
    }

    /**
     * {@code TextChannel.createMessage} returns discord4j's final {@link MessageCreateMono};
     * a bare mock never emits and would hang the reactive chain, so delegate its subscription
     * to a real completing {@link Mono}.
     */
    private static MessageCreateMono messageMono() {
        return mock(MessageCreateMono.class, delegatesTo(Mono.just(mock(Message.class))));
    }
}
