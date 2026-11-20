package cloud.cholewa.notification.discord.skippy.api;

import cloud.cholewa.home.model.HouseholdEvent;
import cloud.cholewa.home.model.HouseholdNotification;
import cloud.cholewa.notification.discord.skippy.service.DiscordBotService;
import cloud.cholewa.notification.household.HouseholdMessage;
import cloud.cholewa.notification.household.sms.MarkedNotificationSms;
import cloud.cholewa.notification.household.sms.SmsSmoke;
import cloud.cholewa.notification.model.NotificationLevel;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

//The smoke test of the SMS (HAS-275) with the SMS switched on: the household message to Discord, and the
//same words by SMS to the administrators - only when asked for by sms=true
@WebFluxTest(DiscordBotController.class)
@ActiveProfiles("test")
class DiscordBotControllerSmsTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private DiscordBotService discordBotService;

    @MockitoBean
    private SmsSmoke smsSmoke;

    @MockitoBean
    private MarkedNotificationSms markedNotificationSms;

    //HAS-335: the smoke test of a marked alert - the Discord post first, then the same text by SMS
    @Test
    void should_text_a_message_marked_for_an_sms_after_posting_it_to_discord() {
        final List<String> order = new ArrayList<>();
        when(discordBotService.sendMessage(NotificationLevel.ERROR, "The grid is lost"))
            .thenReturn(Mono.fromRunnable(() -> order.add("discord")));
        when(markedNotificationSms.send(NotificationLevel.ERROR, "The grid is lost"))
            .thenReturn(Mono.fromRunnable(() -> order.add("sms")));

        webTestClient.get()
            .uri("/skippy?message=The grid is lost&level=error&sms=true")
            .exchange()
            .expectStatus().isOk();

        assertThat(order).containsExactly("discord", "sms");
    }

    //as on the queue: the SMS also when the post failed, and the failure still answered
    @Test
    void should_text_a_marked_message_when_the_discord_post_failed() {
        when(discordBotService.sendMessage(NotificationLevel.ERROR, "The grid is lost"))
            .thenReturn(Mono.error(new IllegalStateException("not deliverable")));
        when(markedNotificationSms.send(NotificationLevel.ERROR, "The grid is lost")).thenReturn(Mono.empty());

        webTestClient.get()
            .uri("/skippy?message=The grid is lost&level=error&sms=true")
            .exchange()
            .expectStatus().is5xxServerError();

        verify(markedNotificationSms).send(NotificationLevel.ERROR, "The grid is lost");
    }

    @Test
    void should_not_text_a_message_without_sms_true() {
        when(discordBotService.sendMessage(NotificationLevel.INFO, "test")).thenReturn(Mono.empty());

        webTestClient.get()
            .uri("/skippy?message=test")
            .exchange()
            .expectStatus().isOk();

        verifyNoInteractions(markedNotificationSms);
    }

    @Test
    void should_text_the_administrators_the_words_posted_to_discord() {
        when(discordBotService.sendHouseholdMessage(any(), any())).thenReturn(Mono.empty());
        when(smsSmoke.sendToAdministrators(any())).thenReturn(Mono.just(1L));

        webTestClient.get()
            .uri("/skippy?category=household&sms=true")
            .exchange()
            .expectStatus().isOk();

        final ArgumentCaptor<HouseholdMessage> posted = ArgumentCaptor.forClass(HouseholdMessage.class);
        verify(discordBotService).sendHouseholdMessage(any(), posted.capture());
        verify(smsSmoke).sendToAdministrators(posted.getValue());
        assertThat(posted.getValue().text()).isEqualTo("Zmywarka skończyła pracę - czas ją opróżnić.");
    }

    //HAS-462: event=fire alarm (and fire alarm over) is a sample of a smoke detector of the kitchen - with sms=true
    //texted to the administrators alone, never to every member as the real alarm is
    @Test
    void should_post_a_fire_alarm_sample_and_text_it_to_the_administrators_alone() {
        when(discordBotService.sendHouseholdMessage(any(), any())).thenReturn(Mono.empty());
        when(smsSmoke.sendToAdministrators(any())).thenReturn(Mono.just(1L));

        webTestClient.get().uri("/skippy?category=household&event=fire alarm&sms=true").exchange().expectStatus().isOk();
        webTestClient.get().uri("/skippy?category=household&event=fire alarm over").exchange().expectStatus().isOk();

        final ArgumentCaptor<HouseholdNotification> event = ArgumentCaptor.forClass(HouseholdNotification.class);
        final ArgumentCaptor<HouseholdMessage> posted = ArgumentCaptor.forClass(HouseholdMessage.class);
        verify(discordBotService, times(2)).sendHouseholdMessage(event.capture(), posted.capture());
        assertThat(event.getAllValues()).extracting(HouseholdNotification::getEvent)
            .containsExactly(HouseholdEvent.FIRE_ALARM, HouseholdEvent.FIRE_ALARM_OVER);
        assertThat(posted.getAllValues().get(0).title()).isEqualTo("Alarm pożarowy");
        assertThat(posted.getAllValues().get(1).text()).isEqualTo("Czujka dymu w pomieszczeniu: kuchnia - alarm ustał.");
        verify(smsSmoke).sendToAdministrators(posted.getAllValues().get(0));
    }

    //HAS-430: event=smog is a sample smog warning on Discord - never texted, so with sms=true it is refused
    @Test
    void should_post_a_smog_sample_and_never_text_it() {
        when(discordBotService.sendHouseholdMessage(any(), any())).thenReturn(Mono.empty());

        webTestClient.get().uri("/skippy?category=household&event=smog").exchange().expectStatus().isOk();
        webTestClient.get().uri("/skippy?category=household&event=smog&sms=true").exchange().expectStatus().isBadRequest();

        final ArgumentCaptor<HouseholdMessage> posted = ArgumentCaptor.forClass(HouseholdMessage.class);
        verify(discordBotService).sendHouseholdMessage(any(), posted.capture());
        assertThat(posted.getValue().title()).isEqualTo("Air quality sensor");
        assertThat(posted.getValue().text()).startsWith("Smog na zewnątrz: PM10 112 µg/m³, PM2.5 78 µg/m³");
        verifyNoInteractions(smsSmoke);
    }

    //HAS-451: event=doorbell is a sample ring on the intercom channel - never texted, so with sms=true it is refused
    @Test
    void should_post_a_doorbell_sample_and_never_text_it() {
        when(discordBotService.sendHouseholdMessage(any(), any())).thenReturn(Mono.empty());

        webTestClient.get().uri("/skippy?category=household&event=doorbell").exchange().expectStatus().isOk();
        webTestClient.get().uri("/skippy?category=household&event=doorbell&sms=true").exchange().expectStatus().isBadRequest();
        webTestClient.get().uri("/skippy?category=household&event=gate opened&sms=true").exchange().expectStatus().isBadRequest();

        final ArgumentCaptor<HouseholdNotification> event = ArgumentCaptor.forClass(HouseholdNotification.class);
        final ArgumentCaptor<HouseholdMessage> posted = ArgumentCaptor.forClass(HouseholdMessage.class);
        verify(discordBotService).sendHouseholdMessage(event.capture(), posted.capture());
        assertThat(event.getValue().getEvent()).isEqualTo(HouseholdEvent.DOORBELL_RINGING);
        assertThat(posted.getValue().title()).isEqualTo("Domofon");
        verifyNoInteractions(smsSmoke);
    }

    //HAS-472: event=garage is a sample cold alarm of a garage gate on the intercom channel - Discord only, so with
    //sms=true it is refused like every intercom event
    @Test
    void should_post_a_garage_sample_and_never_text_it() {
        when(discordBotService.sendHouseholdMessage(any(), any())).thenReturn(Mono.empty());

        webTestClient.get().uri("/skippy?category=household&event=garage").exchange().expectStatus().isOk();
        webTestClient.get().uri("/skippy?category=household&event=garage&sms=true").exchange().expectStatus().isBadRequest();

        final ArgumentCaptor<HouseholdNotification> event = ArgumentCaptor.forClass(HouseholdNotification.class);
        final ArgumentCaptor<HouseholdMessage> posted = ArgumentCaptor.forClass(HouseholdMessage.class);
        verify(discordBotService).sendHouseholdMessage(event.capture(), posted.capture());
        assertThat(event.getValue().getEvent()).isEqualTo(HouseholdEvent.GARAGE_GATE_OPEN_COLD);
        assertThat(posted.getValue().title()).isEqualTo("Brama garażowa Test");
        assertThat(posted.getValue().text()).isEqualTo("Otwarta od 15 min, a na zewnątrz 7,5 °C - zamknij ją.");
        verifyNoInteractions(smsSmoke);
    }

    // an SMS costs and reaches a phone: never without sms=true
    @Test
    void should_text_nobody_without_sms_true() {
        when(discordBotService.sendHouseholdMessage(any(), any())).thenReturn(Mono.empty());

        webTestClient.get()
            .uri("/skippy?category=household")
            .exchange()
            .expectStatus().isOk();

        verifyNoInteractions(smsSmoke);
    }
}
