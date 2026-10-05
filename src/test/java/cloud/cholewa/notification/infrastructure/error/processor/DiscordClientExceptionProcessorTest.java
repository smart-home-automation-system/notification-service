package cloud.cholewa.notification.infrastructure.error.processor;

import cloud.cholewa.commons.error.model.ErrorMessage;
import cloud.cholewa.commons.error.model.Errors;
import discord4j.rest.http.client.ClientException;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DiscordClientExceptionProcessorTest {

    private final DiscordClientExceptionProcessor sut = new DiscordClientExceptionProcessor();

    @Test
    void should_map_discord_refusal_to_bad_gateway_without_the_upstream_message() {
        final ClientException refused = mock(ClientException.class);
        when(refused.getStatus()).thenReturn(HttpResponseStatus.FORBIDDEN);

        final Errors errors = sut.apply(refused);

        assertThat(errors.getHttpStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(errors.getErrors())
            .extracting(ErrorMessage::getMessage)
            .containsExactly("Discord refused the request with status: 403");
    }
}
