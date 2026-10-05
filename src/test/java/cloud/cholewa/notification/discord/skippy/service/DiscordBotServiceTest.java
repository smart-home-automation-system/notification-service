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
import discord4j.rest.http.client.ClientException;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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

    @Test
    void should_look_the_channel_up_only_once() {
        guildWith(channel("alerts", Channel.Type.GUILD_TEXT));
        when(skippy.getChannelById(Snowflake.of(CHANNEL_ID))).thenReturn(restChannel);
        when(restChannel.createMessage(anyString())).thenReturn(Mono.just(mock(MessageData.class)));

        discordBotService.sendMessage("first").as(StepVerifier::create).verifyComplete();
        discordBotService.sendMessage("second").as(StepVerifier::create).verifyComplete();

        verify(skippy, times(1)).getGuilds();
        verify(restChannel).createMessage("first");
        verify(restChannel).createMessage("second");
    }

    @Test
    void should_look_the_channel_up_again_after_discord_answers_404() {
        final ClientException notFound = mock(ClientException.class);
        when(notFound.getStatus()).thenReturn(HttpResponseStatus.NOT_FOUND);

        guildWith(channel("alerts", Channel.Type.GUILD_TEXT));
        when(skippy.getChannelById(Snowflake.of(CHANNEL_ID))).thenReturn(restChannel);
        when(restChannel.createMessage(MESSAGE))
            .thenReturn(Mono.error(notFound))
            .thenReturn(Mono.just(mock(MessageData.class)));

        discordBotService.sendMessage(MESSAGE).as(StepVerifier::create).verifyErrorMatches(notFound::equals);
        discordBotService.sendMessage(MESSAGE).as(StepVerifier::create).verifyComplete();

        verify(skippy, times(2)).getGuilds();
    }

    //so that the caller sees Discord's answer, and /skippy answers 502 instead of the default 500
    @Test
    void should_signal_the_discord_answer_when_its_own_retries_are_exhausted() {
        final ClientException unavailable = mock(ClientException.class);

        guildWith(channel("alerts", Channel.Type.GUILD_TEXT));
        when(skippy.getChannelById(Snowflake.of(CHANNEL_ID))).thenReturn(restChannel);
        when(restChannel.createMessage(MESSAGE))
            .thenReturn(Mono.error(Exceptions.retryExhausted("Retries exhausted: 10/10", unavailable)));

        discordBotService.sendMessage(MESSAGE).as(StepVerifier::create).verifyErrorMatches(unavailable::equals);
    }

    //a failed lookup is not remembered either
    @Test
    void should_look_the_channel_up_again_after_it_was_not_found() {
        when(skippy.getGuilds()).thenReturn(Flux.empty());

        discordBotService.sendMessage(MESSAGE).as(StepVerifier::create).verifyError(NotificationException.class);
        discordBotService.sendMessage(MESSAGE).as(StepVerifier::create).verifyError(NotificationException.class);

        verify(skippy, times(2)).getGuilds();
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
