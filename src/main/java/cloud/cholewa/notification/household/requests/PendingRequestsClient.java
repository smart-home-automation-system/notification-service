package cloud.cholewa.notification.household.requests;

import cloud.cholewa.commons.error.DownstreamErrors;
import cloud.cholewa.home.model.MemberRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * The household requests of database-service >= 0.22.0 (HAS-409) over k8s DNS, on the WebClient of the
 * registry: what is pending - every open request not yet texted of every member switched on - and the
 * marking of what was texted. Read into the {@code MemberRequest} of smart-home-sdk 1.17.0: it carries no
 * enum, so a newer SDK there can never fail the read here. What a failing database-service says goes to the
 * log; no body is a failed call, never an empty list.
 */
@Slf4j
@Component
public class PendingRequestsClient {

    private static final ParameterizedTypeReference<List<MemberRequest>> REQUESTS = new ParameterizedTypeReference<>() {
    };

    private final WebClient databaseWebClient;

    public PendingRequestsClient(@Qualifier("databaseWebClient") final WebClient databaseWebClient) {
        this.databaseWebClient = databaseWebClient;
    }

    public Mono<List<MemberRequest>> pending() {
        return databaseWebClient.get()
            .uri("/home/household/requests?pending=true")
            .retrieve()
            .onStatus(HttpStatusCode::isError, response -> failure(response, "the read of the pending requests"))
            .bodyToMono(REQUESTS)
            .switchIfEmpty(Mono.error(() -> new IllegalStateException("database-service answered without a list")));
    }

    //the number marked: a request sent before or unknown is skipped there
    public Mono<Long> markSent(final List<Long> ids) {
        return databaseWebClient.put()
            .uri("/home/household/requests/sent")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(ids)
            .retrieve()
            .onStatus(HttpStatusCode::isError, response -> failure(response, "the marking of the requests sent"))
            .bodyToMono(Long.class)
            .switchIfEmpty(Mono.error(() -> new IllegalStateException("database-service answered without a number")));
    }

    private static Mono<IllegalStateException> failure(
        final org.springframework.web.reactive.function.client.ClientResponse response, final String what) {
        return DownstreamErrors.read(response)
            .map(error -> {
                log.warn("database-service answered {} to {}: {}", error.status().value(), what, error.errors());
                return new IllegalStateException("database-service answered " + error.status().value());
            });
    }
}
