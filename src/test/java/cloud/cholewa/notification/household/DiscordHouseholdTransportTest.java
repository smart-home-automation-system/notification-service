package cloud.cholewa.notification.household;

import cloud.cholewa.home.model.HouseholdEvent;
import cloud.cholewa.home.model.HouseholdNotification;
import cloud.cholewa.notification.discord.skippy.service.DiscordBotService;
import cloud.cholewa.notification.household.sms.SmsRecipients;
import discord4j.rest.http.client.ClientException;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.List;
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

    @Mock
    private ObjectProvider<SmsRecipients> smsRecipients;

    private DiscordHouseholdTransport sut;

    @BeforeEach
    void setUp() {
        sut = new DiscordHouseholdTransport(discordBotService, smsRecipients);
    }

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

    // HAS-275: the footer names who the SMS of the message goes to
    @Test
    void should_name_in_the_footer_the_members_the_sms_goes_to() {
        final SmsRecipients sms = mock(SmsRecipients.class);
        when(smsRecipients.getIfAvailable()).thenReturn(sms);
        when(sms.recipients()).thenReturn(Mono.just(List.of(
            new SmsRecipients.Recipient("Ola", "+48500000001"), new SmsRecipients.Recipient("Jan", "+48500000002"))));
        final HouseholdMessage texted = MESSAGE.withFooter("SMS: Ola, Jan");
        when(discordBotService.sendHouseholdMessage(texted)).thenReturn(Mono.empty());

        sut.deliver(NOTIFICATION, MESSAGE).as(StepVerifier::create).verifyComplete();

        verify(discordBotService).sendHouseholdMessage(texted);
        assertThat(texted.footer()).isEqualTo(MESSAGE.footer() + " · SMS: Ola, Jan");
    }

    // the message never waits for the read nor fails because of it
    @Test
    void should_leave_the_footer_as_it_is_when_nobody_is_texted_or_the_read_fails() {
        final SmsRecipients sms = mock(SmsRecipients.class);
        when(smsRecipients.getIfAvailable()).thenReturn(sms);
        when(sms.recipients()).thenReturn(Mono.just(List.of()), Mono.error(new IllegalStateException("presence-service answered 503")));
        when(discordBotService.sendHouseholdMessage(MESSAGE)).thenReturn(Mono.empty());

        sut.deliver(NOTIFICATION, MESSAGE).as(StepVerifier::create).verifyComplete();
        sut.deliver(NOTIFICATION, MESSAGE).as(StepVerifier::create).verifyComplete();

        verify(discordBotService, org.mockito.Mockito.times(2)).sendHouseholdMessage(MESSAGE);
    }
}
