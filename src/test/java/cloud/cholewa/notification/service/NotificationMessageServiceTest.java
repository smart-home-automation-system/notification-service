package cloud.cholewa.notification.service;

import cloud.cholewa.notification.discord.skippy.service.DiscordBotService;
import cloud.cholewa.notification.infrastructure.error.NotificationException;
import discord4j.rest.http.client.ClientException;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Answers.RETURNS_SMART_NULLS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationMessageServiceTest {

    private static final String MESSAGE = "Temperature sensor in room office is not reporting";

    @Mock(answer = RETURNS_SMART_NULLS)
    private DiscordBotService discordBotService;

    @InjectMocks
    private NotificationMessageService sut;

    @Test
    void should_deliver_message_to_discord() {
        when(discordBotService.sendMessage(MESSAGE)).thenReturn(Mono.empty());

        sut.processMessage(MESSAGE)
            .as(StepVerifier::create)
            .verifyComplete();

        verify(discordBotService).sendMessage(MESSAGE);
    }

    @Test
    void should_retry_delivery_until_it_succeeds() {
        final AtomicInteger attempts = new AtomicInteger();

        when(discordBotService.sendMessage(MESSAGE)).thenReturn(Mono.defer(() ->
            attempts.incrementAndGet() < 3 ? Mono.error(new IllegalStateException("Discord down")) : Mono.empty()));

        StepVerifier.withVirtualTime(() -> sut.processMessage(MESSAGE))
            .thenAwait(Duration.ofMinutes(10))
            .verifyComplete();

        assertThat(attempts).hasValue(3);
    }

    //the same answer every time: retrying only keeps the message unacknowledged for longer
    @Test
    void should_not_retry_when_the_channel_does_not_exist() {
        final AtomicInteger attempts = new AtomicInteger();

        when(discordBotService.sendMessage(MESSAGE)).thenReturn(Mono.defer(() -> {
            attempts.incrementAndGet();
            return Mono.error(new NotificationException("Discord text channel not found: alerts"));
        }));

        StepVerifier.withVirtualTime(() -> sut.processMessage(MESSAGE))
            .thenAwait(Duration.ofMinutes(10))
            .verifyError(NotificationException.class);

        assertThat(attempts).hasValue(1);
    }

    @ParameterizedTest
    @CsvSource({"400, 1", "401, 1", "403, 1", "404, 5", "500, 5", "503, 5"})
    void should_retry_only_discord_answers_that_can_change(final int status, final int expectedAttempts) {
        final AtomicInteger attempts = new AtomicInteger();
        final ClientException refused = mock(ClientException.class);
        when(refused.getStatus()).thenReturn(HttpResponseStatus.valueOf(status));

        when(discordBotService.sendMessage(MESSAGE)).thenReturn(Mono.defer(() -> {
            attempts.incrementAndGet();
            return Mono.error(refused);
        }));

        StepVerifier.withVirtualTime(() -> sut.processMessage(MESSAGE))
            .thenAwait(Duration.ofMinutes(10))
            .verifyErrorMatches(refused::equals);

        assertThat(attempts).hasValue(expectedAttempts);
    }

    @Test
    void should_signal_the_last_failure_when_retries_are_used_up() {
        final AtomicInteger attempts = new AtomicInteger();

        when(discordBotService.sendMessage(MESSAGE)).thenReturn(Mono.defer(() ->
            Mono.error(new IllegalStateException("Discord down " + attempts.incrementAndGet()))));

        StepVerifier.withVirtualTime(() -> sut.processMessage(MESSAGE))
            .thenAwait(Duration.ofMinutes(10))
            .verifyErrorMatches(throwable -> throwable instanceof IllegalStateException
                && throwable.getMessage().equals("Discord down 5"));

        //the first attempt and four retries
        assertThat(attempts).hasValue((int) NotificationMessageService.DELIVERY_RETRY_ATTEMPTS + 1);
    }
}
