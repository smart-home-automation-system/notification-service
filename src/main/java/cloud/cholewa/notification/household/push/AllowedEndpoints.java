package cloud.cholewa.notification.household.push;

import cloud.cholewa.home.model.PushSubscription;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Which subscriptions may be posted to (HAS-276; shared since HAS-416 by the household transport and the
 * personal push): an https endpoint on a push service of {@code household.push.allowed-hosts} - the registry
 * takes any https address a browser gives. A subscription skipped is a warning naming the host, never the
 * address, which is the subscription itself.
 */
@Slf4j
final class AllowedEndpoints {

    private final List<String> hosts;

    AllowedEndpoints(final List<String> hosts) {
        this.hosts = hosts.stream().map(host -> host.strip().toLowerCase(Locale.ROOT)).toList();
    }

    Optional<URI> of(final PushSubscription subscription) {
        final URI endpoint;
        try {
            endpoint = URI.create(subscription.getEndpoint());
        } catch (IllegalArgumentException | NullPointerException e) {
            log.warn("A push subscription with an endpoint that is no URI was skipped");
            return Optional.empty();
        }
        final String host = endpoint.getHost() == null ? "" : endpoint.getHost().toLowerCase(Locale.ROOT);
        final boolean allowed = "https".equalsIgnoreCase(endpoint.getScheme())
            && hosts.stream().anyMatch(allowedHost -> host.equals(allowedHost) || host.endsWith("." + allowedHost));
        if (!allowed) {
            log.warn("A push subscription on [{}] was skipped: not a push service of household.push.allowed-hosts", host);
            return Optional.empty();
        }
        return Optional.of(endpoint);
    }
}
