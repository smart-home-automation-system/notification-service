package cloud.cholewa.notification.discord.skippy.api;

import cloud.cholewa.notification.discord.skippy.service.DiscordBotService;
import cloud.cholewa.notification.household.HouseholdMessage;
import cloud.cholewa.notification.model.NotificationLevel;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

//without it a run from the IDE has no test profile, and discord.bot.skippy.alerts-channel-id has no
//value outside that document
@ActiveProfiles("test")
@WebFluxTest(DiscordBotController.class)
class DiscordBotControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private DiscordBotService discordBotService;

    @Test
    void should_process_discord_message() {
        when(discordBotService.sendMessage(NotificationLevel.INFO, "test message")).thenReturn(Mono.empty());

        webTestClient.get()
            .uri(uriBuilder -> uriBuilder.path("/skippy")
                .queryParam("message", "test message")
                .build()
            )
            .exchange()
            .expectStatus().isOk();

        verify(discordBotService).sendMessage(NotificationLevel.INFO, "test message");
    }

    @Test
    void should_send_message_with_the_requested_level() {
        when(discordBotService.sendMessage(NotificationLevel.ERROR, "test message")).thenReturn(Mono.empty());

        webTestClient.get()
            .uri(uriBuilder -> uriBuilder.path("/skippy")
                .queryParam("message", "test message")
                .queryParam("level", "error")
                .build()
            )
            .exchange()
            .expectStatus().isOk();

        verify(discordBotService).sendMessage(NotificationLevel.ERROR, "test message");
    }

    //the smoke test of the household channel (HAS-274): worded as the queue words it, Discord only
    @Test
    void should_post_a_household_test_message_to_the_household_channel() {
        when(discordBotService.sendHouseholdMessage(any())).thenReturn(Mono.empty());

        webTestClient.get()
            .uri(uriBuilder -> uriBuilder.path("/skippy")
                .queryParam("category", "household")
                .queryParam("event", "appliance reminder")
                .queryParam("subject", "Pralka")
                .queryParam("reminder", 2)
                .build())
            .exchange()
            .expectStatus().isOk();

        final ArgumentCaptor<HouseholdMessage> sent = ArgumentCaptor.forClass(HouseholdMessage.class);
        verify(discordBotService).sendHouseholdMessage(sent.capture());
        assertThat(sent.getValue().title()).isEqualTo("Pralka");
        assertThat(sent.getValue().text()).isEqualTo("Przypomnienie 2: Pralka nadal czeka na opróżnienie.");
        verify(discordBotService, never()).sendMessage(any(), any());
    }

    //without anything but the category: the dishwasher finished, as the task words it
    @Test
    void should_post_the_default_household_test_message() {
        when(discordBotService.sendHouseholdMessage(any())).thenReturn(Mono.empty());

        webTestClient.get()
            .uri("/skippy?category=household")
            .exchange()
            .expectStatus().isOk();

        final ArgumentCaptor<HouseholdMessage> sent = ArgumentCaptor.forClass(HouseholdMessage.class);
        verify(discordBotService).sendHouseholdMessage(sent.capture());
        assertThat(sent.getValue().text()).isEqualTo("Zmywarka zakończyła pracę - wymaga opróżnienia.");
    }

    @Test
    void should_refuse_an_unknown_household_event() {
        webTestClient.get()
            .uri("/skippy?category=household&event=appliance exploded")
            .exchange()
            .expectStatus().isBadRequest();

        verifyNoInteractions(discordBotService);
    }

    @Test
    void should_return_400_when_message_is_missing() {
        webTestClient.get()
            .uri("/skippy")
            .exchange()
            .expectStatus().isBadRequest();

        verifyNoInteractions(discordBotService);
    }
}