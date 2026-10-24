package cloud.cholewa.notification.household;

import cloud.cholewa.home.model.HouseholdEvent;
import cloud.cholewa.home.model.HouseholdNotification;
import cloud.cholewa.notification.discord.skippy.service.DiscordBotService;
import discord4j.rest.http.client.ClientException;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DiscordHouseholdTransportTest {

    private static final HouseholdNotification NOTIFICATION = HouseholdNotification.builder()
        .event(HouseholdEvent.APPLIANCE_FINISHED).subject("Zmywarka").deviceId(64L)
        .occurredAt(LocalDateTime.of(2026, 10, 24, 19, 5)).reminder(0).build();
    private static final HouseholdMessage MESSAGE = HouseholdMessages.of(NOTIFICATION);

    @Mock
    private DiscordBotService discordBotService;

    @InjectMocks
    private DiscordHouseholdTransport sut;

    @Test
    void should_be_named_discord_in_the_log() {
        assertThat(sut.name()).isEqualTo("discord");
    }

    @Test
    void should_post_the_message_to_the_household_channel() {
        when(discordBotService.sendHouseholdMessage(MESSAGE)).thenReturn(Mono.empty());

        sut.deliver(NOTIFICATION, MESSAGE).as(StepVerifier::create).verifyComplete();

        verify(discordBotService).sendHouseholdMessage(MESSAGE);
    }

    //what another attempt cannot change is not retried - a refusal (4xx) once, then the error
    @Test
    void should_signal_a_refusal_without_retrying_it() {
        final ClientException forbidden = mock(ClientException.class);
        when(forbidden.getStatus()).thenReturn(HttpResponseStatus.FORBIDDEN);
        final AtomicInteger attempts = new AtomicInteger();
        when(discordBotService.sendHouseholdMessage(MESSAGE))
            .thenReturn(Mono.defer(() -> {
                attempts.incrementAndGet();
                return Mono.error(forbidden);
            }));

        sut.deliver(NOTIFICATION, MESSAGE).as(StepVerifier::create).verifyErrorMatches(forbidden::equals);

        assertThat(attempts).hasValue(1);
    }
}
