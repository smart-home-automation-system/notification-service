package cloud.cholewa.notification.service;

import cloud.cholewa.notification.discord.skippy.service.DiscordBotService;
import cloud.cholewa.notification.household.sms.MarkedNotificationSms;
import cloud.cholewa.notification.model.NotificationLevel;
import discord4j.rest.http.client.ClientException;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.timeout.ReadTimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;
import reactor.netty.channel.AbortedException;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Answers.RETURNS_SMART_NULLS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationMessageServiceTest {

    private static final String MESSAGE = "Temperature sensor in room office is not reporting";

    @Mock(answer = RETURNS_SMART_NULLS)
    private DiscordBotService discordBotService;

    @Mock
    private ObjectProvider<MarkedNotificationSms> markedNotificationSmsProvider;

    @Mock
    private MarkedNotificationSms markedNotificationSms;

    @InjectMocks
    private NotificationMessageService sut;

    //HAS-335: the Discord post first, the SMS after it
    @Test
    void should_text_a_marked_message_after_the_discord_post() {
        final List<String> order = new ArrayList<>();
        when(discordBotService.sendMessage(NotificationLevel.ERROR, MESSAGE))
            .thenReturn(Mono.fromRunnable(() -> order.add("discord")));
        when(markedNotificationSmsProvider.getIfAvailable()).thenReturn(markedNotificationSms);
        when(markedNotificationSms.send(NotificationLevel.ERROR, MESSAGE))
            .thenReturn(Mono.fromRunnable(() -> order.add("sms")));

        sut.processMessage(NotificationLevel.ERROR, MESSAGE, true).as(StepVerifier::create).verifyComplete();

        assertThat(order).containsExactly("discord", "sms");
    }

    @Test
    void should_not_text_an_unmarked_message() {
        when(discordBotService.sendMessage(NotificationLevel.ERROR, MESSAGE)).thenReturn(Mono.empty());

        sut.processMessage(NotificationLevel.ERROR, MESSAGE, false).as(StepVerifier::create).verifyComplete();

        verifyNoInteractions(markedNotificationSmsProvider);
    }

    //the SMS matters most when Discord is down: it still goes out, and the failure of Discord is still told
    @Test
    void should_text_a_marked_message_when_discord_failed_for_good() {
        when(discordBotService.sendMessage(NotificationLevel.ERROR, MESSAGE))
            .thenReturn(Mono.error(new IllegalStateException("not deliverable")));
        when(markedNotificationSmsProvider.getIfAvailable()).thenReturn(markedNotificationSms);
        when(markedNotificationSms.send(NotificationLevel.ERROR, MESSAGE)).thenReturn(Mono.empty());

        sut.processMessage(NotificationLevel.ERROR, MESSAGE, true)
            .as(StepVerifier::create)
            .verifyErrorMessage("not deliverable");

        verify(markedNotificationSms).send(NotificationLevel.ERROR, MESSAGE);
    }

    @Test
    void should_deliver_a_marked_message_to_discord_alone_while_the_sms_is_switched_off() {
        when(discordBotService.sendMessage(NotificationLevel.INFO, MESSAGE)).thenReturn(Mono.empty());
        when(markedNotificationSmsProvider.getIfAvailable()).thenReturn(null);

        sut.processMessage(NotificationLevel.INFO, MESSAGE, true).as(StepVerifier::create).verifyComplete();

        verify(discordBotService).sendMessage(NotificationLevel.INFO, MESSAGE);
    }

    @Test
    void should_deliver_message_to_discord() {
        when(discordBotService.sendMessage(NotificationLevel.WARN, MESSAGE)).thenReturn(Mono.empty());

        sut.processMessage(NotificationLevel.WARN, MESSAGE, false)
            .as(StepVerifier::create)
            .verifyComplete();

        verify(discordBotService).sendMessage(NotificationLevel.WARN, MESSAGE);
    }

    @Test
    void should_retry_delivery_until_it_succeeds() {
        final AtomicInteger attempts = new AtomicInteger();

        when(discordBotService.sendMessage(NotificationLevel.WARN, MESSAGE)).thenReturn(Mono.defer(() ->
            attempts.incrementAndGet() < 3 ? Mono.error(new IOException("connection reset")) : Mono.empty()));

        StepVerifier.withVirtualTime(() -> sut.processMessage(NotificationLevel.WARN, MESSAGE, false))
            .thenAwait(Duration.ofMinutes(10))
            .verifyComplete();

        assertThat(attempts).hasValue(3);
    }

    //the same answer every time: retrying only keeps the message unacknowledged for longer
    @Test
    void should_not_retry_a_fault_of_the_service_itself() {
        final AtomicInteger attempts = new AtomicInteger();

        when(discordBotService.sendMessage(NotificationLevel.WARN, MESSAGE)).thenReturn(Mono.defer(() -> {
            attempts.incrementAndGet();
            return Mono.error(new IllegalStateException("not deliverable"));
        }));

        StepVerifier.withVirtualTime(() -> sut.processMessage(NotificationLevel.WARN, MESSAGE, false))
            .thenAwait(Duration.ofMinutes(10))
            .verifyError(IllegalStateException.class);

        assertThat(attempts).hasValue(1);
    }

    @ParameterizedTest
    @CsvSource({"400, 1", "401, 1", "403, 1", "404, 1", "500, 5", "503, 5"})
    void should_retry_only_discord_answers_that_can_change(final int status, final int expectedAttempts) {
        final AtomicInteger attempts = new AtomicInteger();
        final ClientException refused = mock(ClientException.class);
        when(refused.getStatus()).thenReturn(HttpResponseStatus.valueOf(status));

        when(discordBotService.sendMessage(NotificationLevel.WARN, MESSAGE)).thenReturn(Mono.defer(() -> {
            attempts.incrementAndGet();
            return Mono.error(refused);
        }));

        StepVerifier.withVirtualTime(() -> sut.processMessage(NotificationLevel.WARN, MESSAGE, false))
            .thenAwait(Duration.ofMinutes(10))
            .verifyErrorMatches(refused::equals);

        assertThat(attempts).hasValue(expectedAttempts);
    }

    @Test
    void should_retry_network_failure_wrapped_by_the_client() {
        final AtomicInteger attempts = new AtomicInteger();

        when(discordBotService.sendMessage(NotificationLevel.WARN, MESSAGE)).thenReturn(Mono.defer(() -> attempts.incrementAndGet() < 2
            ? Mono.error(new IllegalStateException("request failed", ReadTimeoutException.INSTANCE))
            : Mono.empty()));

        StepVerifier.withVirtualTime(() -> sut.processMessage(NotificationLevel.WARN, MESSAGE, false))
            .thenAwait(Duration.ofMinutes(10))
            .verifyComplete();

        assertThat(attempts).hasValue(2);
    }

    //what discord4j signals for a 5xx once its own retries are used up
    @Test
    void should_retry_discord_server_error_wrapped_in_retries_exhausted() {
        final AtomicInteger attempts = new AtomicInteger();
        final ClientException unavailable = mock(ClientException.class);
        when(unavailable.getStatus()).thenReturn(HttpResponseStatus.SERVICE_UNAVAILABLE);

        when(discordBotService.sendMessage(NotificationLevel.WARN, MESSAGE)).thenReturn(Mono.defer(() -> attempts.incrementAndGet() < 2
            ? Mono.error(Exceptions.retryExhausted("Retries exhausted: 10/10", unavailable))
            : Mono.empty()));

        StepVerifier.withVirtualTime(() -> sut.processMessage(NotificationLevel.WARN, MESSAGE, false))
            .thenAwait(Duration.ofMinutes(10))
            .verifyComplete();

        assertThat(attempts).hasValue(2);
    }

    @Test
    void should_retry_connection_closed_before_send() {
        final AtomicInteger attempts = new AtomicInteger();

        when(discordBotService.sendMessage(NotificationLevel.WARN, MESSAGE)).thenReturn(Mono.defer(() -> attempts.incrementAndGet() < 2
            ? Mono.error(AbortedException.beforeSend())
            : Mono.empty()));

        StepVerifier.withVirtualTime(() -> sut.processMessage(NotificationLevel.WARN, MESSAGE, false))
            .thenAwait(Duration.ofMinutes(10))
            .verifyComplete();

        assertThat(attempts).hasValue(2);
    }

    //a fault in this service: the next attempt would meet it again
    @Test
    void should_not_retry_failure_that_is_neither_discord_nor_the_network() {
        final AtomicInteger attempts = new AtomicInteger();

        when(discordBotService.sendMessage(NotificationLevel.WARN, MESSAGE)).thenReturn(Mono.defer(() -> {
            attempts.incrementAndGet();
            return Mono.error(new NullPointerException("channel"));
        }));

        StepVerifier.withVirtualTime(() -> sut.processMessage(NotificationLevel.WARN, MESSAGE, false))
            .thenAwait(Duration.ofMinutes(10))
            .verifyError(NullPointerException.class);

        assertThat(attempts).hasValue(1);
    }

    @Test
    void should_signal_the_last_failure_when_retries_are_used_up() {
        final AtomicInteger attempts = new AtomicInteger();

        when(discordBotService.sendMessage(NotificationLevel.WARN, MESSAGE)).thenReturn(Mono.defer(() ->
            Mono.error(new IOException("connection reset " + attempts.incrementAndGet()))));

        StepVerifier.withVirtualTime(() -> sut.processMessage(NotificationLevel.WARN, MESSAGE, false))
            .thenAwait(Duration.ofMinutes(10))
            .verifyErrorMatches(throwable -> throwable instanceof IOException
                && throwable.getMessage().equals("connection reset 5"));

        //the first attempt and four retries
        assertThat(attempts).hasValue((int) DiscordDeliveryRetry.ATTEMPTS + 1);
    }
}
