package cloud.cholewa.notification.household.sms;

import cloud.cholewa.home.model.HouseholdEvent;
import cloud.cholewa.home.model.HouseholdNotification;
import cloud.cholewa.notification.household.HouseholdMessages;
import cloud.cholewa.notification.household.members.HouseholdRegistryClient;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SmsSmokeTest {

    private final HouseholdRegistryClient registry = mock(HouseholdRegistryClient.class);
    private final SmsApiClient client = mock(SmsApiClient.class);
    private final SmsSmoke sut = new SmsSmoke(registry, client);

    //HAS-522: the smoke test texts what a real notification of the sample sends - the title first for load shedding -,
    //to the administrators alone
    @Test
    void should_text_the_administrators_the_sms_a_real_notification_sends() {
        final HouseholdNotification started = HouseholdNotification.builder()
            .event(HouseholdEvent.LOAD_SHEDDING_STARTED).subject("SOLAX").deviceId(1L)
            .occurredAt(LocalDateTime.of(2026, 11, 28, 10, 0)).reminder(0).devices(List.of("Czajnik")).build();
        when(registry.members()).thenReturn(Mono.just(List.of(
            new HouseholdRegistryClient.Member("Jan", "+48500000002", true, "admin"),
            new HouseholdRegistryClient.Member("Ola", "+48500000001", true, "resident"))));
        when(client.send(anyString(), anyString(), anyString())).thenReturn(Mono.just(true));

        sut.sendToAdministrators(started, HouseholdMessages.of(started))
            .as(StepVerifier::create)
            .expectNext(1L)
            .verifyComplete();

        verify(client).send("Jan", "+48500000002", "Praca na baterii: Brak prądu z sieci - dom działa na baterii."
            + " Nie używaj płyty indukcyjnej ani piekarnika. Wyłączone: Czajnik.");
        verify(client, never()).send(eq("Ola"), anyString(), anyString());
    }
}
