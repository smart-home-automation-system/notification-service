package cloud.cholewa.notification.household.push;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * The Web Push transport of the household category (HAS-276).<br>
 * {@code enabled} - without it no push transport exists; the Discord channel still gets everything.<br>
 * {@code subject} - who the push services may contact about this sender, {@code mailto:} or
 * {@code https:}; part of the VAPID token (RFC 8292). From the manifest: it is the owner's address.<br>
 * {@code publicKey} / {@code privateKey} - the VAPID pair as {@code web-push generate-vapid-keys} prints it:
 * base64url, the public key the 65 bytes of an uncompressed P-256 point, the private key its 32-byte scalar.
 * The same public key is what database-service hands the installed application to subscribe with.<br>
 * {@code ttl} - how long a push service keeps a message for a phone that is off (header {@code TTL}); a
 * bare number is minutes. An hour, like the queue the message came from.<br>
 * {@code allowedHosts} - the push services posted to: a host is taken when it is one of these or below
 * one. The endpoint of a subscription is whatever a browser said, and the registry takes any https
 * address - this list is what keeps the service from posting to anywhere else.
 */
@Validated
@ConfigurationProperties(prefix = "household.push")
public record WebPushProperties(
    boolean enabled,
    String subject,
    String publicKey,
    String privateKey,
    @NotNull @DurationMin(minutes = 1) @DurationMax(hours = 24) @DurationUnit(ChronoUnit.MINUTES)
    Duration ttl,
    @NotEmpty List<String> allowedHosts
) {
}
