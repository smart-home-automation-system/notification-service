package cloud.cholewa.notification.household.push;

import cloud.cholewa.home.model.PushSubscription;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.URI;
import java.time.Duration;

/**
 * Posts one encrypted message to the push service of one browser (RFC 8030): the body of
 * {@link WebPushEncryptor}, the token of {@link VapidSigner}, {@code TTL} of the configuration,
 * and {@code Urgency: high} (an appliance waits for somebody now). **No {@code Topic}**: Apple refused
 * ours ({@code appliance-133}, valid by RFC 8030) with 400 {@code BadWebPushTopic} on the first real push
 * (HAS-276). The {@code tag} of the payload still replaces the notification of the same appliance on
 * the phone; only a push service holding several for a phone that is off delivers them all.
 * <p>
 * What it came to is an {@link Outcome}, never an error: 201 (or any 2xx) delivered, 404 and 410 a
 * subscription the push service dropped for good, anything else - a refusal, a 429, a 5xx, no answer -
 * failed for this time. Nothing is retried: the next reminder is the next attempt, and the Discord
 * channel has the message anyway.
 */
@Slf4j
class WebPushSender {

    static final Duration TIMEOUT = Duration.ofSeconds(15);
    // the reason of a refusal in the log: enough for a sentence, never a page
    static final int REASON_LENGTH = 200;

    private final WebClient pushWebClient;
    private final WebPushEncryptor encryptor;
    private final VapidSigner signer;
    private final Duration ttl;

    WebPushSender(
        @Qualifier("pushWebClient") final WebClient pushWebClient,
        final WebPushEncryptor encryptor,
        final VapidSigner signer,
        final Duration ttl
    ) {
        this.pushWebClient = pushWebClient;
        this.encryptor = encryptor;
        this.signer = signer;
        this.ttl = ttl;
    }

    enum Outcome { DELIVERED, GONE, FAILED }

    //the endpoint as the transport parsed it and checked against the allowed hosts
    Mono<Outcome> send(final URI endpoint, final PushSubscription subscription, final byte[] payload) {
        final String host = endpoint.getHost();
        //the encryption and the token throw on keys a browser gave wrong - that is this subscription
        //failing, never the message. The key pair, the ECDH and the signature are CPU work of a few
        //milliseconds: on the parallel scheduler, not on the event loop that answered the read before
        return Mono.fromCallable(() -> new Prepared(
                encryptor.encrypt(payload, subscription.getKeys().getP256dh(), subscription.getKeys().getAuth()),
                signer.authorization(endpoint)))
            .subscribeOn(Schedulers.parallel())
            .onErrorResume(cause -> Mono.fromRunnable(() -> log.warn(
                "A push message for a browser on [{}] could not be prepared: {} - {}",
                host, cause.getClass().getSimpleName(), cause.getMessage())))
            .flatMap(prepared -> post(endpoint, prepared, host))
            .defaultIfEmpty(Outcome.FAILED);
    }

    private Mono<Outcome> post(final URI endpoint, final Prepared prepared, final String host) {
        return pushWebClient.post()
            .uri(endpoint)
            .header(HttpHeaders.AUTHORIZATION, prepared.authorization())
            .header("TTL", String.valueOf(ttl.toSeconds()))
            .header("Urgency", "high")
            .header(HttpHeaders.CONTENT_ENCODING, "aes128gcm")
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .bodyValue(prepared.body())
            .exchangeToMono(response -> Mono.just(response)
                .filter(answer -> answer.statusCode().is2xxSuccessful())
                .flatMap(delivered -> delivered.releaseBody().thenReturn(Outcome.DELIVERED))
                //a refusal says why in its body (Apple: {"reason":"BadJwtToken"}) - read for the log; a body
                //that cannot be read (too big, not text) is no reason, and the status still counts
                .switchIfEmpty(Mono.defer(() -> response.bodyToMono(String.class)
                    .onErrorReturn("")
                    .defaultIfEmpty("")
                    .map(refusal -> outcomeOf(response.statusCode().value(), host, reasonOf(refusal))))))
            .timeout(TIMEOUT)
            .onErrorResume(throwable -> Mono.fromRunnable(() -> log.warn(
                    "A push message for a browser on [{}] got no answer: {} - {}",
                    host, throwable.getClass().getSimpleName(), throwable.getMessage()))
                .thenReturn(Outcome.FAILED));
    }

    //the encrypted body and the VAPID header of one message for one browser - a class, not a record: a
    //record of an array compares the reference, never the bytes (Sonar S6218), and nothing compares these
    private static final class Prepared {

        private final byte[] body;
        private final String authorization;

        private Prepared(final byte[] body, final String authorization) {
            this.body = body;
            this.authorization = authorization;
        }

        byte[] body() {
            return body;
        }

        String authorization() {
            return authorization;
        }
    }

    private static Outcome outcomeOf(final int status, final String host, final String reason) {
        if (status == HttpStatus.NOT_FOUND.value() || status == HttpStatus.GONE.value()) {
            log.info("The push service on [{}] answered {}: the subscription is gone - {}", host, status, reason);
            return Outcome.GONE;
        }
        log.warn("The push service on [{}] answered {} to a push message: {}", host, status, reason);
        return Outcome.FAILED;
    }

    /**
     * What a push service says when it refuses, for the log: the beginning of its body, on one line. It
     * names the cause (Apple: {@code {"reason":"BadWebPushTopic"}}, Google and Mozilla a sentence) and
     * holds neither the address of the subscription nor a key - those are in the request, never logged.
     */
    static String reasonOf(final String body) {
        final String line = body.replaceAll("\\s+", " ").strip();
        if (line.isEmpty()) {
            return "no reason given";
        }
        return line.length() <= REASON_LENGTH ? line : line.substring(0, REASON_LENGTH) + "...";
    }
}
