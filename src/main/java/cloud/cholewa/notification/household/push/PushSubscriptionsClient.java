package cloud.cholewa.notification.household.push;

import cloud.cholewa.commons.error.DownstreamErrors;
import cloud.cholewa.home.model.PushSubscription;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * The push subscriptions of the household, from database-service over k8s DNS (HAS-276): the read of the
 * members at home, keys included, and the removal of a subscription a push service gave up on. Both sit
 * below {@code /home/push-subscriptions}, which the gateway does not route.
 * <p>
 * A subscription is never logged: its endpoint is the address anybody could post to that browser with,
 * its keys what the message is encrypted with. The calls themselves are logged by Logbook at trace, the
 * accepted rule of the organization (database-service logs the same body as it answers it).
 */
@Slf4j
class PushSubscriptionsClient {

    private static final ParameterizedTypeReference<List<PushSubscription>> SUBSCRIPTIONS = new ParameterizedTypeReference<>() {
    };

    private final WebClient databaseWebClient;

    PushSubscriptionsClient(@Qualifier("databaseWebClient") final WebClient databaseWebClient) {
        this.databaseWebClient = databaseWebClient;
    }

    //the names travel comma-separated, so a name holding a comma cannot be asked for - the caller leaves
    //it out (the known limit of the read of database-service)
    Mono<List<PushSubscription>> ofMembers(final List<String> members) {
        return databaseWebClient
            .get()
            .uri(uri -> uri.path("/home/push-subscriptions").queryParam("members", "{members}").build(String.join(",", members)))
            .retrieve()
            .onStatus(HttpStatusCode::isError, response -> DownstreamErrors.read(response)
                .map(error -> {
                    log.warn("database-service answered {} to the read of the push subscriptions: {}",
                        error.status().value(), error.errors());
                    return new IllegalStateException("database-service answered " + error.status().value());
                }))
            .bodyToMono(SUBSCRIPTIONS)
            .switchIfEmpty(Mono.error(() -> new IllegalStateException("database-service answered without a list")));
    }

    Mono<Void> remove(final String endpoint) {
        return databaseWebClient
            .delete()
            .uri(uri -> uri.path("/home/push-subscriptions").queryParam("endpoint", "{endpoint}").build(endpoint))
            .retrieve()
            .onStatus(HttpStatusCode::isError, response -> DownstreamErrors.read(response)
                .map(error -> {
                    log.warn("database-service answered {} to the removal of a push subscription: {}",
                        error.status().value(), error.errors());
                    return new IllegalStateException("database-service answered " + error.status().value());
                }))
            .toBodilessEntity()
            .then();
    }
}
