package cloud.cholewa.notification.household.members;

import cloud.cholewa.commons.error.DownstreamErrors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * The members of the household with their phones and roles, from the registry of database-service over k8s
 * DNS (`GET /home/household`, HAS-275) - for the SMS to the members at home and the SMS of the smoke test.
 * <p>
 * Read into a record of its own, never the {@code HouseholdMember} of smart-home-sdk: the whole registry
 * holds rooms and devices whose enums grow with the SDK, and a value this service does not know would fail
 * the read of a phone. A phone is never logged.
 */
@Slf4j
public class HouseholdRegistryClient {

    private static final ParameterizedTypeReference<List<Member>> MEMBERS = new ParameterizedTypeReference<>() {
    };

    private final WebClient databaseWebClient;

    public HouseholdRegistryClient(@Qualifier("databaseWebClient") final WebClient databaseWebClient) {
        this.databaseWebClient = databaseWebClient;
    }

    public Mono<List<Member>> members() {
        return databaseWebClient
            .get()
            .uri("/home/household")
            .retrieve()
            .onStatus(HttpStatusCode::isError, response -> DownstreamErrors.read(response)
                .map(error -> {
                    log.warn("database-service answered {} to the read of the household: {}",
                        error.status().value(), error.errors());
                    return new IllegalStateException("database-service answered " + error.status().value());
                }))
            .bodyToMono(MEMBERS)
            .switchIfEmpty(Mono.error(() -> new IllegalStateException("database-service answered without a list")));
    }

    /** The part of a member of the registry read here; {@code role} as text - an unknown one is no admin. */
    public record Member(String name, String phone, Boolean active, String role) {

        public boolean switchedOn() {
            return Boolean.TRUE.equals(active);
        }

        public boolean admin() {
            return "admin".equalsIgnoreCase(role);
        }
    }
}
