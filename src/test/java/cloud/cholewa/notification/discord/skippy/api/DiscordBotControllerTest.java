package cloud.cholewa.notification.discord.skippy.api;

import cloud.cholewa.notification.discord.skippy.service.DiscordBotService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@WebFluxTest(DiscordBotController.class)
class DiscordBotControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private DiscordBotService discordBotService;

    @Test
    void should_process_discord_message() {
        when(discordBotService.sendMessage(anyString())).thenReturn(Mono.empty());

        webTestClient.get()
            .uri(uriBuilder -> uriBuilder.path("/skippy")
                .queryParam("message", "test message")
                .build()
            )
            .exchange()
            .expectStatus().isOk();

        verify(discordBotService).sendMessage(anyString());
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