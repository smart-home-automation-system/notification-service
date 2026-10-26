package cloud.cholewa.notification.household.members;

import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

//The household of database-service, over a real HTTP exchange (HAS-275)
class HouseholdRegistryClientTest {

    private MockWebServer server;
    private HouseholdRegistryClient sut;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        sut = new HouseholdRegistryClient(WebClient.builder().baseUrl(server.url("/").toString()).build());
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    // a room or a device this service does not know never fails the read of a phone
    @Test
    void should_read_name_phone_active_and_role_whatever_else_a_member_holds() throws InterruptedException {
        server.enqueue(new MockResponse.Builder().code(200).setHeader("Content-Type", "application/json").body("""
            [{"name":"Ola","phone":"+48500000001","active":true,"role":"admin","rooms":["a room of 2030"],
              "devices":[{"name":"Phone","mac":"02:00:00:00:00:01","kind":"unknown"}]},
             {"name":"Jan","phone":"+48500000002","active":false,"role":"guest"}]""").build());

        sut.members()
            .as(StepVerifier::create)
            .assertNext(members -> {
                assertThat(members).hasSize(2);
                assertThat(members.get(0).admin()).isTrue();
                assertThat(members.get(0).switchedOn()).isTrue();
                assertThat(members.get(1).admin()).isFalse();
                assertThat(members.get(1).switchedOn()).isFalse();
            })
            .verifyComplete();

        assertThat(server.takeRequest().getUrl().encodedPath()).isEqualTo("/home/household");
    }

    @Test
    void should_take_an_error_or_no_list_as_a_failed_read() {
        server.enqueue(new MockResponse.Builder().code(500).build());
        server.enqueue(new MockResponse.Builder().code(200).build());

        sut.members().as(StepVerifier::create).expectErrorMessage("database-service answered 500").verify();
        sut.members().as(StepVerifier::create).expectErrorMessage("database-service answered without a list").verify();
    }
}
