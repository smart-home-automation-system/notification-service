package cloud.cholewa.notification.household.sms;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

/**
 * The SMS of the household category (HAS-275), through SMSAPI.<br>
 * {@code enabled} - without it there is no SMS transport; Discord and the push still get everything.<br>
 * {@code url} - scheme and host of the API of SMSAPI ({@code https://api.smsapi.pl}); a dead address in the
 * tests.<br>
 * {@code sender} - the sender name registered at SMSAPI ({@code smart-home}, active since 2026-10-24).<br>
 * {@code token} - the OAuth token of the account, from the secret {@code notification}; never logged.<br>
 * {@code connectTimeout} / {@code responseTimeout} - a bare number is seconds.
 */
@Validated
@ConfigurationProperties(prefix = "household.sms")
public record SmsProperties(
    boolean enabled,
    @NotBlank String url,
    String sender,
    String token,
    @NotNull @DurationMin(seconds = 1) @DurationMax(seconds = 60) @DurationUnit(ChronoUnit.SECONDS)
    Duration connectTimeout,
    @NotNull @DurationMin(seconds = 1) @DurationMax(seconds = 60) @DurationUnit(ChronoUnit.SECONDS)
    Duration responseTimeout
) {
}
