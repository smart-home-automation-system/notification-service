package cloud.cholewa.notification.household.sms;

import cloud.cholewa.home.model.HouseholdEvent;
import cloud.cholewa.home.model.HouseholdNotification;
import cloud.cholewa.notification.household.HouseholdMessage;
import cloud.cholewa.notification.household.HouseholdMessages;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SmsHouseholdTransportTest {

    private static final HouseholdNotification FINISHED = HouseholdNotification.builder()
        .event(HouseholdEvent.APPLIANCE_FINISHED).subject("Washing machine").deviceId(131L)
        .occurredAt(LocalDateTime.of(2026, 10, 26, 11, 30)).reminder(0).acknowledgePath("/room").build();
    private static final HouseholdMessage MESSAGE = HouseholdMessages.of(FINISHED);
    private static final SmsRecipients.Recipient OLA = new SmsRecipients.Recipient("Ola", "+48500000001");
    private static final SmsRecipients.Recipient JAN = new SmsRecipients.Recipient("Jan", "+48500000002");

    private final SmsRecipients recipients = mock(SmsRecipients.class);
    private final SmsApiClient client = mock(SmsApiClient.class);
    private final SmsHouseholdTransport sut = new SmsHouseholdTransport(recipients, client);

    @Test
    void should_be_named_sms_in_the_log() {
        assertThat(sut.name()).isEqualTo("sms");
    }

    @Test
    void should_text_every_member_at_home_the_words_of_the_message() {
        when(recipients.recipients()).thenReturn(Mono.just(List.of(OLA, JAN)));
        when(client.send(anyString(), anyString(), anyString())).thenReturn(Mono.just(true));

        sut.deliver(FINISHED, MESSAGE).as(StepVerifier::create).verifyComplete();

        verify(client).send("Ola", "+48500000001", "Pralka skończyła pracę - czas ją opróżnić.");
        verify(client).send(eq("Jan"), eq("+48500000002"), any());
    }

    // Discord has the message
    @Test
    void should_text_nobody_while_nobody_is_at_home() {
        when(recipients.recipients()).thenReturn(Mono.just(List.of()));

        sut.deliver(FINISHED, MESSAGE).as(StepVerifier::create).verifyComplete();

        verifyNoInteractions(client);
    }

    // never an SMS to everybody instead
    @Test
    void should_fail_when_it_cannot_learn_who_is_at_home() {
        when(recipients.recipients()).thenReturn(Mono.error(new IllegalStateException("presence-service answered 503")));

        sut.deliver(FINISHED, MESSAGE).as(StepVerifier::create).expectErrorMessage("presence-service answered 503").verify();

        verifyNoInteractions(client);
    }

    @Test
    void should_be_done_when_one_sms_went_out_and_fail_when_none_did() {
        when(recipients.recipients()).thenReturn(Mono.just(List.of(OLA, JAN)));
        when(client.send(eq("Ola"), anyString(), anyString())).thenReturn(Mono.just(false));
        when(client.send(eq("Jan"), anyString(), anyString())).thenReturn(Mono.just(true), Mono.just(false));

        sut.deliver(FINISHED, MESSAGE).as(StepVerifier::create).verifyComplete();
        sut.deliver(FINISHED, MESSAGE).as(StepVerifier::create).expectErrorMessage("No SMS was accepted by SMSAPI").verify();
    }

    @Test
    void should_cut_a_text_to_one_sms_by_code_points() {
        final HouseholdMessage longOne = new HouseholdMessage("Dryer", "ą".repeat(200), "26.10.2026 11:30", 0);
        final HouseholdMessage shortOne = new HouseholdMessage("Dryer", "  krótko  ", "26.10.2026 11:30", 0);

        assertThat(SmsText.of(longOne)).hasSize(SmsText.MAX_LENGTH).endsWith("…");
        assertThat(SmsText.of(shortOne)).isEqualTo("krótko");
    }

    @Test
    void should_refuse_to_start_without_the_token_or_the_sender() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> SmsConfig.checked(" ", "household.sms.token"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("household.sms.token is required while household.sms.enabled is on");
        assertThat(SmsConfig.checked(" smart-home ", "household.sms.sender")).isEqualTo("smart-home");
    }
}
