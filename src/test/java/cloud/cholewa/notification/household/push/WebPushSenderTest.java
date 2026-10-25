package cloud.cholewa.notification.household.push;

import cloud.cholewa.home.model.PushSubscription;
import cloud.cholewa.home.model.PushSubscriptionKeys;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.time.Clock;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

//One message to one push service, over a real HTTP exchange (HAS-276)
class WebPushSenderTest {

    private static final byte[] PAYLOAD = "{\"notification\":{}}".getBytes(StandardCharsets.UTF_8);

    private MockWebServer server;
    private WebPushSender sut;
    private PushSubscription subscription;

    @BeforeEach
    void setUp() throws IOException, GeneralSecurityException {
        server = new MockWebServer();
        server.start();
        final KeyPair vapid = VapidSignerTest.pair();
        final VapidSigner signer = new VapidSigner((ECPrivateKey) vapid.getPrivate(),
            P256.encode(P256.point((ECPublicKey) vapid.getPublic())), "mailto:owner@example.com", Clock.systemUTC());
        sut = new WebPushSender(WebClient.builder().build(), new WebPushEncryptor(), signer, Duration.ofHours(1));

        final KeyPair browser = VapidSignerTest.pair();
        subscription = PushSubscription.builder()
            .endpoint(endpoint().toString())
            .keys(PushSubscriptionKeys.builder()
                .p256dh(P256.encode(P256.point((ECPublicKey) browser.getPublic())))
                .auth("BTBZMqHH6r4Tts7J_aSIgg")
                .build())
            .build();
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void should_post_the_encrypted_message_with_the_headers_of_web_push() throws InterruptedException {
        server.enqueue(new MockResponse.Builder().code(201).build());

        sut.send(endpoint(), subscription, PAYLOAD, "appliance-133")
            .as(StepVerifier::create)
            .expectNext(WebPushSender.Outcome.DELIVERED)
            .verifyComplete();

        final RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getUrl().encodedPath()).isEqualTo("/push/abc");
        assertThat(request.getHeaders().get("TTL")).isEqualTo("3600");
        assertThat(request.getHeaders().get("Urgency")).isEqualTo("high");
        assertThat(request.getHeaders().get("Topic")).isEqualTo("appliance-133");
        assertThat(request.getHeaders().get("Content-Encoding")).isEqualTo("aes128gcm");
        assertThat(request.getHeaders().get("Content-Type")).isEqualTo("application/octet-stream");
        assertThat(request.getHeaders().get("Authorization")).startsWith("vapid t=").contains(", k=");
        //the header of RFC 8188 and the ciphertext: never the plaintext
        assertThat(request.getBody().size()).isEqualTo(86L + PAYLOAD.length + 1 + 16);
        assertThat(request.getBody().utf8()).doesNotContain("notification");
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 410})
    void should_tell_a_subscription_the_push_service_dropped(final int status) {
        server.enqueue(new MockResponse.Builder().code(status).build());

        sut.send(endpoint(), subscription, PAYLOAD, "appliance-133")
            .as(StepVerifier::create)
            .expectNext(WebPushSender.Outcome.GONE)
            .verifyComplete();
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 403, 413, 429, 500, 503})
    void should_tell_any_other_answer_as_failed_for_this_time(final int status) {
        server.enqueue(new MockResponse.Builder().code(status).build());

        sut.send(endpoint(), subscription, PAYLOAD, "appliance-133")
            .as(StepVerifier::create)
            .expectNext(WebPushSender.Outcome.FAILED)
            .verifyComplete();
    }

    //keys a browser gave wrong fail that browser, never the message
    @Test
    void should_fail_a_subscription_with_keys_that_cannot_be_used_without_calling() {
        final PushSubscription broken = PushSubscription.builder()
            .endpoint(subscription.getEndpoint())
            .keys(PushSubscriptionKeys.builder().p256dh("BAAA").auth("AAAA").build())
            .build();

        sut.send(endpoint(), broken, PAYLOAD, "appliance-133")
            .as(StepVerifier::create)
            .expectNext(WebPushSender.Outcome.FAILED)
            .verifyComplete();

        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void should_fail_a_push_service_that_is_not_there() {
        final URI nowhere = URI.create("http://localhost:1/push/abc");

        sut.send(nowhere, subscription, PAYLOAD, "appliance-133")
            .as(StepVerifier::create)
            .expectNext(WebPushSender.Outcome.FAILED)
            .verifyComplete();
    }

    private URI endpoint() {
        return server.url("/push/abc").uri();
    }
}
