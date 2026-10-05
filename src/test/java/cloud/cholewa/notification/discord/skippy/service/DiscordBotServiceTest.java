package cloud.cholewa.notification.discord.skippy.service;

import cloud.cholewa.notification.infrastructure.error.NotificationException;
import discord4j.common.util.Snowflake;
import discord4j.core.DiscordClient;
import discord4j.core.object.entity.channel.Channel;
import discord4j.discordjson.Id;
import discord4j.discordjson.json.ChannelData;
import discord4j.discordjson.json.MessageData;
import discord4j.discordjson.json.UserGuildData;
import discord4j.discordjson.possible.Possible;
import discord4j.rest.entity.RestChannel;
import discord4j.rest.entity.RestGuild;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DiscordBotServiceTest {

    private static final String MESSAGE = "test message";
    private static final long GUILD_ID = 1L;
    private static final long CHANNEL_ID = 10L;

    @Mock
    private DiscordClient skippy;

    @Mock
    private RestChannel restChannel;

    @InjectMocks
    private DiscordBotService discordBotService;

    @Test
    void should_send_message_to_alerts_text_channel() {
        guildWith(channel("alerts", Channel.Type.GUILD_TEXT));
        when(skippy.getChannelById(Snowflake.of(CHANNEL_ID))).thenReturn(restChannel);
        when(restChannel.createMessage(MESSAGE)).thenReturn(Mono.just(mock(MessageData.class)));

        discordBotService.sendMessage(MESSAGE)
            .as(StepVerifier::create)
            .verifyComplete();

        verify(restChannel).createMessage(MESSAGE);
    }

    @Test
    void should_match_alerts_channel_ignoring_case() {
        guildWith(channel("ALERTS", Channel.Type.GUILD_TEXT));
        when(skippy.getChannelById(Snowflake.of(CHANNEL_ID))).thenReturn(restChannel);
        when(restChannel.createMessage(MESSAGE)).thenReturn(Mono.just(mock(MessageData.class)));

        discordBotService.sendMessage(MESSAGE)
            .as(StepVerifier::create)
            .verifyComplete();

        verify(restChannel).createMessage(MESSAGE);
    }

    @Test
    void should_never_open_a_gateway_session() {
        guildWith(channel("alerts", Channel.Type.GUILD_TEXT));
        when(skippy.getChannelById(Snowflake.of(CHANNEL_ID))).thenReturn(restChannel);
        when(restChannel.createMessage(MESSAGE)).thenReturn(Mono.just(mock(MessageData.class)));

        discordBotService.sendMessage(MESSAGE)
            .as(StepVerifier::create)
            .verifyComplete();

        verify(skippy, never()).login();
    }

    @Test
    void should_fail_when_only_other_text_channels_exist() {
        guildWith(channel("general", Channel.Type.GUILD_TEXT));

        discordBotService.sendMessage(MESSAGE)
            .as(StepVerifier::create)
            .verifyErrorMatches(throwable -> throwable instanceof NotificationException
                && throwable.getMessage().equals("Discord text channel not found: alerts"));

        verify(skippy, never()).getChannelById(any());
    }

    @Test
    void should_ignore_non_text_channel_named_alerts() {
        guildWith(channel("alerts", Channel.Type.GUILD_VOICE));

        discordBotService.sendMessage(MESSAGE)
            .as(StepVerifier::create)
            .verifyError(NotificationException.class);

        verify(skippy, never()).getChannelById(any());
    }

    @Test
    void should_fail_when_no_guilds_available() {
        when(skippy.getGuilds()).thenReturn(Flux.empty());

        discordBotService.sendMessage(MESSAGE)
            .as(StepVerifier::create)
            .verifyError(NotificationException.class);
    }

    @Test
    void should_fail_when_discord_refuses_the_message() {
        guildWith(channel("alerts", Channel.Type.GUILD_TEXT));
        when(skippy.getChannelById(Snowflake.of(CHANNEL_ID))).thenReturn(restChannel);
        when(restChannel.createMessage(MESSAGE)).thenReturn(Mono.error(new IllegalStateException("403")));

        discordBotService.sendMessage(MESSAGE)
            .as(StepVerifier::create)
            .verifyError(IllegalStateException.class);
    }

    private void guildWith(final ChannelData channel) {
        final UserGuildData guild = mock(UserGuildData.class);
        final RestGuild restGuild = mock(RestGuild.class);

        when(guild.id()).thenReturn(Id.of(GUILD_ID));
        when(skippy.getGuilds()).thenReturn(Flux.just(guild));
        when(skippy.getGuildById(Snowflake.of(GUILD_ID))).thenReturn(restGuild);
        when(restGuild.getChannels()).thenReturn(Flux.just(channel));
    }

    private static ChannelData channel(final String name, final Channel.Type type) {
        final ChannelData channel = mock(ChannelData.class);

        when(channel.type()).thenReturn(type.getValue());
        //not read for a channel of another type, and the id only for the channel that matches
        lenient().when(channel.name()).thenReturn(Possible.of(name));
        lenient().when(channel.id()).thenReturn(Id.of(CHANNEL_ID));
        return channel;
    }
}
