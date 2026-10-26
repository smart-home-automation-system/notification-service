package cloud.cholewa.notification.discord.skippy.api;

import cloud.cholewa.notification.discord.skippy.service.DiscordBotService;
import cloud.cholewa.notification.household.HouseholdMessage;
import cloud.cholewa.notification.household.sms.SmsSmoke;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

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
        assertThat(posted.getValue().text()).isEqualTo("Koniec pracy: Zmywarka - wymaga opróżnienia.");
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
