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

    @MockitoBean
    private RequestsReminders reminders;

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

    //HAS-416: the announcements and the evening by hand, 202 with what each did
    @Test
    void should_run_the_reminders_once_and_answer_202_with_what_they_did() {
        when(reminders.announceDone()).thenReturn(Mono.just(new RequestsReminders.Outcome(1, 2)));
        when(reminders.evening()).thenReturn(Mono.just(new RequestsReminders.Outcome(3, 4)));

        webTestClient.post().uri("/requests/announce").exchange()
            .expectStatus().isAccepted()
            .expectBody().jsonPath("$.messages").isEqualTo(1).jsonPath("$.marked").isEqualTo(2);
        webTestClient.post().uri("/requests/remind").exchange()
            .expectStatus().isAccepted()
            .expectBody().jsonPath("$.messages").isEqualTo(3).jsonPath("$.marked").isEqualTo(4);

        verify(reminders).announceDone();
        verify(reminders).evening();
    }
}
