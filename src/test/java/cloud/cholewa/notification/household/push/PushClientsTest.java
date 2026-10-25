package cloud.cholewa.notification.household.push;

import cloud.cholewa.home.model.PushSubscription;
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

//The two services the push transport reads, over a real HTTP exchange (HAS-276)
class PushClientsTest {

    private MockWebServer server;
    private WebClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        client = WebClient.builder().baseUrl(server.url("/").toString()).build();
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void should_name_the_members_presence_service_says_are_at_home() throws InterruptedException {
        server.enqueue(json("""
            [{"name":"Ola","present":true,"since":"2026-10-25T08:00:00"},
             {"name":"Jan","present":false},
             {"name":"Nowy"}]"""));

        new PresentMembersClient(client).present()
            .as(StepVerifier::create)
            .expectNext(List.of("Ola"))
            .verifyComplete();

        assertThat(server.takeRequest().getUrl().encodedPath()).isEqualTo("/home/presence/residents/presence");
    }

    //an empty answer would read as a house nobody is in, and quietly send nothing
    @Test
    void should_take_no_list_from_presence_service_as_a_failed_call() {
        server.enqueue(new MockResponse.Builder().code(200).build());

        new PresentMembersClient(client).present()
            .as(StepVerifier::create)
            .expectErrorMessage("presence-service answered without a list")
            .verify();
    }

    @Test
    void should_take_an_error_of_presence_service_as_a_failed_call() {
        server.enqueue(new MockResponse.Builder().code(503).build());

        new PresentMembersClient(client).present()
            .as(StepVerifier::create)
            .expectErrorMessage("presence-service answered 503")
            .verify();
    }

    @Test
    void should_read_the_subscriptions_of_the_members_at_home() throws InterruptedException {
        server.enqueue(json("""
            [{"endpoint":"https://web.push.apple.com/abc","keys":{"p256dh":"BAAA","auth":"AAAA"}}]"""));

        new PushSubscriptionsClient(client).ofMembers(List.of("Ola", "Jan Kowalski"))
            .as(StepVerifier::create)
            .assertNext(found -> assertThat(found).extracting(PushSubscription::getEndpoint)
                .containsExactly("https://web.push.apple.com/abc"))
            .verifyComplete();

        final RecordedRequest request = server.takeRequest();
        assertThat(request.getUrl().encodedPath()).isEqualTo("/home/push-subscriptions");
        assertThat(request.getUrl().queryParameter("members")).isEqualTo("Ola,Jan Kowalski");
    }

    @Test
    void should_take_an_error_of_database_service_as_a_failed_read() {
        server.enqueue(new MockResponse.Builder().code(500).build());

        new PushSubscriptionsClient(client).ofMembers(List.of("Ola"))
            .as(StepVerifier::create)
            .expectErrorMessage("database-service answered 500")
            .verify();
    }

    //the endpoint is a URL with its own query - it must arrive whole, as one parameter
    @Test
    void should_remove_a_subscription_by_its_endpoint() throws InterruptedException {
        server.enqueue(new MockResponse.Builder().code(204).build());
        final String endpoint = "https://fcm.googleapis.com/fcm/send/a:b?c=d&e=f";

        new PushSubscriptionsClient(client).remove(endpoint)
            .as(StepVerifier::create)
            .verifyComplete();

        final RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("DELETE");
        assertThat(request.getUrl().encodedPath()).isEqualTo("/home/push-subscriptions");
        assertThat(request.getUrl().queryParameter("endpoint")).isEqualTo(endpoint);
    }

    @Test
    void should_take_a_refused_removal_as_a_failure() {
        server.enqueue(new MockResponse.Builder().code(400).build());

        new PushSubscriptionsClient(client).remove("https://web.push.apple.com/abc")
            .as(StepVerifier::create)
            .expectErrorMessage("database-service answered 400")
            .verify();
    }

    private static MockResponse json(final String body) {
        return new MockResponse.Builder().code(200).setHeader("Content-Type", "application/json").body(body).build();
    }
}
