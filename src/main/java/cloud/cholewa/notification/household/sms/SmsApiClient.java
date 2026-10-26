package cloud.cholewa.notification.household.sms;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

/**
 * One SMS through the API of SMSAPI (HAS-275): {@code POST /sms.do} with the token as a bearer, the
 * number, the text, the registered sender, {@code format=json} and {@code encoding=utf-8}.
 * <p>
 * What it came to is {@code true} or {@code false}, never an error: SMSAPI answers a refusal with an error
 * object - with a 4xx, or with a 200 - and the code is what says why (14 a sender not registered, 101 a
 * token refused, 103 no points left, 13 a number it cannot reach). It is logged at ERROR with the code alone and
 * the member, **never the number, the text or the token**. Nothing is retried: an SMS sent twice costs
 * twice and reaches somebody twice.
 */
@Slf4j
class SmsApiClient {

    static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final WebClient smsApiWebClient;
    private final String sender;
    private final String token;
    private final JsonMapper jsonMapper;

    SmsApiClient(
        @Qualifier("smsApiWebClient") final WebClient smsApiWebClient,
        final String sender,
        final String token,
        final JsonMapper jsonMapper
    ) {
        this.smsApiWebClient = smsApiWebClient;
        this.sender = sender;
        this.token = token;
        this.jsonMapper = jsonMapper;
    }

    /** @param member who the number belongs to - for the log, which never holds the number */
    Mono<Boolean> send(final String member, final String phone, final String text) {
        final MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("to", phone);
        form.add("message", text);
        form.add("from", sender);
        form.add("format", "json");
        form.add("encoding", "utf-8");

        return smsApiWebClient.post()
            .uri("/sms.do")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(BodyInserters.fromFormData(form))
            .exchangeToMono(response -> response.bodyToMono(String.class)
                .onErrorReturn("")
                .defaultIfEmpty("")
                .map(body -> outcomeOf(member, response.statusCode().value(), body)))
            .timeout(TIMEOUT)
            .onErrorResume(throwable -> {
                log.error("SMS to household member [{}] got no answer from SMSAPI: {} - {}",
                    member, throwable.getClass().getSimpleName(), throwable.getMessage());
                return Mono.just(false);
            });
    }

    private boolean outcomeOf(final String member, final int status, final String body) {
        final JsonNode answer = parsed(body);
        final JsonNode error = answer == null ? null : answer.get("error");
        if (status >= 200 && status < 300 && answer != null && (error == null || error.isNull()) && answer.has("count")) {
            log.info("SMS to household member [{}] accepted by SMSAPI", member);
            return true;
        }
        //the code alone: the message of SMSAPI may repeat the number it refused
        final String code = error == null || error.isNull() ? "none" : error.asString();
        log.error("SMS to household member [{}] refused by SMSAPI: status {}, error {}", member, status, code);
        return false;
    }

    private JsonNode parsed(final String body) {
        try {
            return body.isBlank() ? null : jsonMapper.readTree(body);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
