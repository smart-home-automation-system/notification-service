package cloud.cholewa.notification.household.sms;

import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

//One SMS through SMSAPI, over a real HTTP exchange (HAS-275)
@ExtendWith(OutputCaptureExtension.class)
class SmsApiClientTest {

    private static final String PHONE = "+48500000001";
    private static final String TOKEN = "test-token-not-real";
    private static final String TEXT = "Koniec pracy: Zmywarka - wymaga opróżnienia.";

    private MockWebServer server;
    private SmsApiClient sut;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        sut = new SmsApiClient(WebClient.builder().baseUrl(server.url("/").toString()).build(), "smart-home", TOKEN,
            JsonMapper.builder().build());
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void should_post_the_sms_with_the_token_the_sender_and_utf_8(final CapturedOutput output) throws InterruptedException {
        server.enqueue(json(200, "{\"count\":1,\"list\":[{\"id\":\"1\",\"points\":0.16,\"status\":\"QUEUE\"}]}"));

        sut.send("Ola", PHONE, TEXT)
            .as(StepVerifier::create)
            .expectNext(true)
            .verifyComplete();

        final RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getUrl().encodedPath()).isEqualTo("/sms.do");
        assertThat(request.getHeaders().get("Authorization")).isEqualTo("Bearer " + TOKEN);
        final String form = URLDecoder.decode(request.getBody().utf8(), StandardCharsets.UTF_8);
        assertThat(form).contains("to=" + PHONE, "message=" + TEXT, "from=smart-home", "format=json", "encoding=utf-8");
        assertThat(output).contains("SMS to household member [Ola] accepted by SMSAPI");
    }

    // SMSAPI answers some refusals with a 200 and an error object, others with a 4xx
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "200|{\"error\":14,\"message\":\"Invalid from field\"}|14",
        "401|{\"error\":101,\"message\":\"Authorization failed\"}|101",
        "400|{\"error\":13,\"message\":\"No correct phone numbers\"}|13"
    })
    void should_tell_a_refusal_by_its_error_code_without_the_number_or_the_token(
        final int status, final String body, final String code, final CapturedOutput output
    ) {
        server.enqueue(json(status, body));

        sut.send("Ola", PHONE, TEXT)
            .as(StepVerifier::create)
            .expectNext(false)
            .verifyComplete();

        assertThat(output).contains("refused by SMSAPI: status " + status + ", error " + code);
        assertThat(output).doesNotContain(PHONE).doesNotContain(TOKEN).doesNotContain("Zmywarka");
    }

    @Test
    void should_take_an_answer_that_is_no_json_as_a_refusal() {
        server.enqueue(new MockResponse.Builder().code(502).body("<html>bad gateway</html>").build());

        sut.send("Ola", PHONE, TEXT)
            .as(StepVerifier::create)
            .expectNext(false)
            .verifyComplete();
    }

    @Test
    void should_take_no_answer_as_a_failed_send(final CapturedOutput output) {
        final SmsApiClient nowhere = new SmsApiClient(WebClient.builder().baseUrl("http://localhost:1").build(),
            "smart-home", TOKEN, JsonMapper.builder().build());

        nowhere.send("Ola", PHONE, TEXT)
            .as(StepVerifier::create)
            .expectNext(false)
            .verifyComplete();

        assertThat(output).contains("SMS to household member [Ola] got no answer from SMSAPI").doesNotContain(PHONE);
    }

    private static MockResponse json(final int status, final String body) {
        return new MockResponse.Builder().code(status).setHeader("Content-Type", "application/json").body(body).build();
    }
}
