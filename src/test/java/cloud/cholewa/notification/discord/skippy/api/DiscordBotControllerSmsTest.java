package cloud.cholewa.notification.discord.skippy.api;

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
        when(discordBotService.sendHouseholdMessage(any())).thenReturn(Mono.empty());
        when(smsSmoke.sendToAdministrators(any())).thenReturn(Mono.just(1L));

        webTestClient.get()
            .uri("/skippy?category=household&sms=true")
            .exchange()
            .expectStatus().isOk();

        final ArgumentCaptor<HouseholdMessage> posted = ArgumentCaptor.forClass(HouseholdMessage.class);
        verify(discordBotService).sendHouseholdMessage(posted.capture());
        verify(smsSmoke).sendToAdministrators(posted.getValue());
        assertThat(posted.getValue().text()).isEqualTo("Zmywarka skończyła pracę - czas ją opróżnić.");
    }

    // an SMS costs and reaches a phone: never without sms=true
    @Test
    void should_text_nobody_without_sms_true() {
        when(discordBotService.sendHouseholdMessage(any())).thenReturn(Mono.empty());

        webTestClient.get()
            .uri("/skippy?category=household")
            .exchange()
            .expectStatus().isOk();

        verifyNoInteractions(smsSmoke);
    }
}
