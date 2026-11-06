package cloud.cholewa.notification.household.requests;

import cloud.cholewa.home.model.MemberRequest;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

//The household requests of database-service 0.22.0 over a real HTTP exchange (HAS-410)
class PendingRequestsClientTest {

    private MockWebServer server;
    private PendingRequestsClient sut;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        sut = new PendingRequestsClient(WebClient.builder().baseUrl(server.url("/").toString()).build());
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void should_read_the_pending_requests() throws InterruptedException {
        server.enqueue(json("""
            [{"id":7,"member":"Liwia","text":"papier toaletowy","author":"Borys","createdAt":"2026-11-06T18:30:00",
              "unknown":"a field of a newer database-service"}]"""));

        sut.pending().as(StepVerifier::create)
            .assertNext(requests -> assertThat(requests).extracting(MemberRequest::getId, MemberRequest::getMember,
                MemberRequest::getText).containsExactly(org.assertj.core.groups.Tuple.tuple(7L, "Liwia", "papier toaletowy")))
            .verifyComplete();

        final RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("GET");
        assertThat(request.getUrl().encodedPath()).isEqualTo("/home/household/requests");
        assertThat(request.getUrl().queryParameter("pending")).isEqualTo("true");
    }

    @Test
    void should_mark_the_requests_sent_and_answer_the_number() throws InterruptedException {
        server.enqueue(json("2"));

        sut.markSent(List.of(7L, 8L)).as(StepVerifier::create).expectNext(2L).verifyComplete();

        final RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("PUT");
        assertThat(request.getUrl().encodedPath()).isEqualTo("/home/household/requests/sent");
        assertThat(request.getBody().utf8()).isEqualTo("[7,8]");
    }

    //an error, or no body, is a failed call - never an empty list
    @Test
    void should_take_an_error_or_no_body_as_a_failed_call() {
        server.enqueue(new MockResponse.Builder().code(503).build());
        server.enqueue(new MockResponse.Builder().code(200).build());
        server.enqueue(new MockResponse.Builder().code(500).build());

        sut.pending().as(StepVerifier::create).expectErrorMessage("database-service answered 503").verify();
        sut.pending().as(StepVerifier::create).expectErrorMessage("database-service answered without a list").verify();
        sut.markSent(List.of(7L)).as(StepVerifier::create).expectErrorMessage("database-service answered 500").verify();
    }

    private static MockResponse json(final String body) {
        return new MockResponse.Builder().code(200).setHeader("Content-Type", "application/json").body(body).build();
    }
}
