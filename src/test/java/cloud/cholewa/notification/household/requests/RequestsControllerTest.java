package cloud.cholewa.notification.household.requests;

import cloud.cholewa.notification.config.ExceptionHandlerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//The SMS of the household requests by hand (HAS-410): the pass of 08:00 once, 202 with what it did
@Import(ExceptionHandlerConfig.class)
@WebFluxTest(controllers = RequestsController.class)
class RequestsControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private RequestsSmsPass pass;

    @Test
    void should_run_the_pass_once_and_answer_202_with_what_it_did() {
        when(pass.run()).thenReturn(Mono.just(new RequestsSmsPass.Outcome(2, 3)));

        webTestClient.post().uri("/requests/send").exchange()
            .expectStatus().isAccepted()
            .expectBody()
            .jsonPath("$.sms").isEqualTo(2)
            .jsonPath("$.marked").isEqualTo(3);

        verify(pass).run();
    }
}
