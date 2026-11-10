package cloud.cholewa.notification.household.requests;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * The household requests (HAS-410, HAS-416, epic HAS-407), pinned by RequestsPropertiesTest.<br>
 * {@code enabled} - the passes are scheduled (on in the cluster, off in {@code local}); the manual triggers work
 * either way.<br>
 * {@code sendAt} - the morning SMS, a cron of Spring, six fields ({@code 0 0 8 * * *}), in {@code zone}.<br>
 * {@code remindAt} - the evening reminder and the announcements of the requests still open (HAS-416), a cron in
 * {@code zone} ({@code 0 0 20 * * *}).<br>
 * {@code announceEvery} - how often the requests ticked off are told to their authors (HAS-416), 10 s to 10 min, a
 * bare number in seconds.<br>
 * {@code staleAfter} - how long after its SMS an open request is told to its author as still open (HAS-416), 1 to
 * 30 days, a bare number in days.<br>
 * {@code zone} - the zone of the house: a pod runs in UTC, so 08:00 without it would be 09:00 or 10:00 of the
 * house.
 */
@Validated
@ConfigurationProperties(prefix = "household.requests")
public record RequestsProperties(
    boolean enabled,
    @NotBlank String sendAt,
    @NotBlank String remindAt,
    @NotNull @DurationMin(seconds = 10) @DurationMax(minutes = 10) @DurationUnit(ChronoUnit.SECONDS) Duration announceEvery,
    @NotNull @DurationMin(days = 1) @DurationMax(days = 30) @DurationUnit(ChronoUnit.DAYS) Duration staleAfter,
    @NotNull ZoneId zone
) {
}
