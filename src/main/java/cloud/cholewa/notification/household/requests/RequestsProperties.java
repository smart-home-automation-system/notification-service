package cloud.cholewa.notification.household.requests;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.ZoneId;

/**
 * The SMS of the household requests (HAS-410, epic HAS-407), pinned by RequestsPropertiesTest.<br>
 * {@code enabled} - the daily pass is scheduled (on in the cluster, off in {@code local}); the manual
 * trigger works either way.<br>
 * {@code sendAt} - a cron of Spring, six fields ({@code 0 0 8 * * *}), in {@code zone}.<br>
 * {@code zone} - the zone of the house: a pod runs in UTC, so 08:00 without it would be 09:00 or 10:00 of the
 * house.
 */
@Validated
@ConfigurationProperties(prefix = "household.requests")
public record RequestsProperties(boolean enabled, @NotBlank String sendAt, @NotNull ZoneId zone) {
}
