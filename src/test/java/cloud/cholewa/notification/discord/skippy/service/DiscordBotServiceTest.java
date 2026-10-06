package cloud.cholewa.notification.discord.skippy.service;

import cloud.cholewa.notification.discord.skippy.config.DiscordBotConfig;
import cloud.cholewa.notification.model.NotificationLevel;
import discord4j.common.util.Snowflake;
import discord4j.core.DiscordClient;
import discord4j.discordjson.json.EmbedData;
import discord4j.discordjson.json.MessageCreateRequest;
import discord4j.discordjson.json.MessageData;
import discord4j.rest.entity.RestChannel;
import discord4j.rest.http.client.ClientException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DiscordBotServiceTest {

    private static final String MESSAGE = "test message";
    private static final String CHANNEL_ID = "1466923100024344650";

    @Mock
    private DiscordClient skippy;

    @Mock
    private RestChannel restChannel;

    @Captor
    private ArgumentCaptor<MessageCreateRequest> requestCaptor;

    private DiscordBotService discordBotService;

    @BeforeEach
    void setUp() {
        discordBotService = new DiscordBotService(skippy, new DiscordBotConfig("token", CHANNEL_ID));
    }

    @Test
    void should_send_one_embed_with_the_level_as_title_and_the_text_inside() {
        discordAccepts();

        discordBotService.sendMessage(NotificationLevel.ERROR, MESSAGE)
            .as(StepVerifier::create)
            .verifyComplete();

        final MessageCreateRequest request = sentRequest();
        final EmbedData embed = request.embeds().get().getFirst();

        assertThat(request.embeds().get()).hasSize(1);
        assertThat(embed.title().get()).isEqualTo("ERROR");
        assertThat(embed.description().get()).isEqualTo(MESSAGE);
        assertThat(embed.color().get()).isEqualTo(0xE74C3C);
        //the text is not repeated outside the embed
        assertThat(request.content().isAbsent()).isTrue();
    }

    //thrown while the request is built, before discord4j returns anything to subscribe to
    @Test
    void should_signal_a_failure_to_build_the_request_instead_of_throwing_it() {
        discordBotService.sendMessage(NotificationLevel.ERROR, null)
            .as(StepVerifier::create)
            .verifyError(NullPointerException.class);
    }

    //a title alone would say that something happened and not what
    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\n"})
    void should_send_a_placeholder_for_blank_text(final String blank) {
        discordAccepts();

        discordBotService.sendMessage(NotificationLevel.INFO, blank).as(StepVerifier::create).verifyComplete();

        assertThat(description()).isEqualTo(DiscordBotService.EMPTY_MESSAGE);
    }

    @ParameterizedTest
    @CsvSource({"ERROR, 15158332", "WARN, 15844367", "INFO, 3066993"})
    void should_color_the_embed_by_level(final NotificationLevel level, final int color) {
        discordAccepts();

        discordBotService.sendMessage(level, MESSAGE).as(StepVerifier::create).verifyComplete();

        final EmbedData badge = sentRequest().embeds().get().getFirst();
        assertThat(badge.title().get()).isEqualTo(level.name());
        assertThat(badge.color().get()).isEqualTo(color);
    }

    //Discord answers 400 for a longer description, and a 400 is not retried
    @Test
    void should_cut_message_longer_than_discord_accepts() {
        discordAccepts();

        discordBotService.sendMessage(NotificationLevel.INFO, "x".repeat(5000))
            .as(StepVerifier::create)
            .verifyComplete();

        assertThat(description()).hasSizeLessThanOrEqualTo(DiscordBotService.MAX_DESCRIPTION_LENGTH).endsWith("…");
    }

    //half of an emoji at the cut would be refused like a message that is too long
    @Test
    void should_not_cut_through_a_surrogate_pair() {
        discordAccepts();

        //an odd number of leading characters puts every emoji across an even/odd boundary
        discordBotService.sendMessage(NotificationLevel.INFO, "x" + "🔥".repeat(2500))
            .as(StepVerifier::create)
            .verifyComplete();

        final String content = description();
        final String withoutMarker = content.substring(0, content.length() - 1);

        assertThat(content).hasSizeLessThanOrEqualTo(DiscordBotService.MAX_DESCRIPTION_LENGTH);
        assertThat(Character.isHighSurrogate(withoutMarker.charAt(withoutMarker.length() - 1))).isFalse();
    }

    @Test
    void should_keep_message_of_exactly_the_limit() {
        final String message = "x".repeat(DiscordBotService.MAX_DESCRIPTION_LENGTH);
        discordAccepts();

        discordBotService.sendMessage(NotificationLevel.INFO, message).as(StepVerifier::create).verifyComplete();

        assertThat(description()).isEqualTo(message);
    }

    //listing them makes discord4j decode every channel of the server, and one it cannot decode
    //fails the delivery - which is how 0.3.0 delivered nothing; login() leaves a session open
    @Test
    void should_neither_list_guilds_nor_open_a_gateway_session() {
        discordAccepts();

        discordBotService.sendMessage(NotificationLevel.ERROR, MESSAGE)
            .as(StepVerifier::create)
            .verifyComplete();

        verify(skippy, never()).getGuilds();
        verify(skippy, never()).login();
    }

    @Test
    void should_fail_when_discord_refuses_the_message() {
        final ClientException forbidden = mock(ClientException.class);

        when(skippy.getChannelById(Snowflake.of(CHANNEL_ID))).thenReturn(restChannel);
        when(restChannel.createMessage(any(MessageCreateRequest.class))).thenReturn(Mono.error(forbidden));

        discordBotService.sendMessage(NotificationLevel.ERROR, MESSAGE)
            .as(StepVerifier::create)
            .verifyErrorMatches(forbidden::equals);
    }

    //so that the caller sees Discord's answer, and /skippy answers 502 instead of the default 500
    @Test
    void should_signal_the_discord_answer_when_its_own_retries_are_exhausted() {
        final ClientException unavailable = mock(ClientException.class);

        when(skippy.getChannelById(Snowflake.of(CHANNEL_ID))).thenReturn(restChannel);
        when(restChannel.createMessage(any(MessageCreateRequest.class)))
            .thenReturn(Mono.error(Exceptions.retryExhausted("Retries exhausted: 10/10", unavailable)));

        discordBotService.sendMessage(NotificationLevel.ERROR, MESSAGE)
            .as(StepVerifier::create)
            .verifyErrorMatches(unavailable::equals);
    }

    @Test
    void should_refuse_channel_id_that_is_not_a_number() {
        final DiscordBotConfig config = new DiscordBotConfig("token", "alerts");

        assertThatThrownBy(() -> new DiscordBotService(skippy, config))
            .isInstanceOf(NumberFormatException.class);
    }

    private void discordAccepts() {
        when(skippy.getChannelById(Snowflake.of(CHANNEL_ID))).thenReturn(restChannel);
        when(restChannel.createMessage(any(MessageCreateRequest.class)))
            .thenReturn(Mono.just(mock(MessageData.class)));
    }

    private String description() {
        return sentRequest().embeds().get().getFirst().description().get();
    }

    private MessageCreateRequest sentRequest() {
        verify(restChannel).createMessage(requestCaptor.capture());
        return requestCaptor.getValue();
    }
}
