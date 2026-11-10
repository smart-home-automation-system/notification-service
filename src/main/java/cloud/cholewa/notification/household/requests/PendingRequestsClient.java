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

import java.time.LocalDateTime;
import java.util.List;

/**
 * The household requests of database-service >= 0.22.0 (HAS-409) over k8s DNS, on the WebClient of the
 * registry: what is pending - every open request not yet texted of every member switched on - and the
 * marking of what was texted; since HAS-416 (database-service >= 0.24.0) the reads and the marks of the
 * evening reminders and of what an author is told. Read into the {@code MemberRequest} of smart-home-sdk: it
 * carries no enum, so a newer SDK there can never fail the read here. The times go as the local time of the
 * house, as a {@code LocalDateTime} prints. What a failing database-service says goes to the
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
        return mark("sent", ids);
    }

    /** HAS-416: the requests ticked off whose authors were not told yet. */
    public Mono<List<MemberRequest>> doneToAnnounce() {
        return read("/home/household/requests/done-to-announce", "the read of the requests done");
    }

    /** HAS-416: the open texted requests of the members switched on, not reminded since the time given. */
    public Mono<List<MemberRequest>> toRemind(final LocalDateTime remindedBefore) {
        return read("/home/household/requests/to-remind?remindedBefore=" + remindedBefore, "the read of the requests to remind");
    }

    /** HAS-416: the open requests texted before the time given whose authors were never told they are open. */
    public Mono<List<MemberRequest>> stale(final LocalDateTime sentBefore) {
        return read("/home/household/requests/stale?sentBefore=" + sentBefore, "the read of the requests still open");
    }

    /** HAS-416: {@code reminded}, {@code done-announced} or {@code stale-announced} - the number marked. */
    public Mono<Long> mark(final String what, final List<Long> ids) {
        return databaseWebClient.put()
            .uri("/home/household/requests/" + what)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(ids)
            .retrieve()
            .onStatus(HttpStatusCode::isError, response -> failure(response, "the marking of the requests " + what))
            .bodyToMono(Long.class)
            .switchIfEmpty(Mono.error(() -> new IllegalStateException("database-service answered without a number")));
    }

    private Mono<List<MemberRequest>> read(final String uri, final String what) {
        return databaseWebClient.get()
            .uri(uri)
            .retrieve()
            .onStatus(HttpStatusCode::isError, response -> failure(response, what))
            .bodyToMono(REQUESTS)
            .switchIfEmpty(Mono.error(() -> new IllegalStateException("database-service answered without a list")));
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
