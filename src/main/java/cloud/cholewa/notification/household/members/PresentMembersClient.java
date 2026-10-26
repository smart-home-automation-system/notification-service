package cloud.cholewa.notification.household.members;

import cloud.cholewa.commons.error.DownstreamErrors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Objects;

/**
 * Who of the household is at home, from presence-service over k8s DNS - not through the gateway (HAS-276,
 * read by the Web Push and the SMS of HAS-275):
 * the push goes to the members at home only, the Discord channel to everybody (owner, 2026-10-11).
 * <p>
 * What a failing presence-service says goes to the log; no body, or one that is not a list, is a failed
 * call - never an empty house, which would quietly send nothing. A resident whose {@code present} is
 * missing is not at home: nothing says they are.
 */
@Slf4j
public class PresentMembersClient {

    private static final ParameterizedTypeReference<List<ResidentPresence>> RESIDENTS = new ParameterizedTypeReference<>() {
    };

    private final WebClient presenceWebClient;

    public PresentMembersClient(@Qualifier("presenceWebClient") final WebClient presenceWebClient) {
        this.presenceWebClient = presenceWebClient;
    }

    public Mono<List<String>> present() {
        return presenceWebClient
            .get()
            .uri("/home/presence/residents/presence")
            .retrieve()
            .onStatus(HttpStatusCode::isError, response -> DownstreamErrors.read(response)
                .map(error -> {
                    log.warn("presence-service answered {} to the read of the residents: {}",
                        error.status().value(), error.errors());
                    return new IllegalStateException("presence-service answered " + error.status().value());
                }))
            .bodyToMono(RESIDENTS)
            .switchIfEmpty(Mono.error(() -> new IllegalStateException("presence-service answered without a list")))
            .map(residents -> residents.stream()
                .filter(Objects::nonNull)
                .filter(resident -> Boolean.TRUE.equals(resident.present()) && resident.name() != null)
                .map(ResidentPresence::name)
                .toList());
    }

    //the part of the answer of presence-service read here
    record ResidentPresence(String name, Boolean present) {
    }
}
