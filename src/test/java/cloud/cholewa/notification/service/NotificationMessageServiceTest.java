package cloud.cholewa.notification.service;

import cloud.cholewa.notification.discord.skippy.service.DiscordBotService;
import org.junit.jupiter.api.Test;
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
