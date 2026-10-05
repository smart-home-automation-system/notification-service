package cloud.cholewa.notification.discord.skippy.api;

import cloud.cholewa.notification.discord.skippy.service.DiscordBotService;
import cloud.cholewa.notification.model.NotificationLevel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

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

    @Test
    void should_return_400_when_message_is_missing() {
        webTestClient.get()
            .uri("/skippy")
            .exchange()
            .expectStatus().isBadRequest();

        verifyNoInteractions(discordBotService);
    }
}