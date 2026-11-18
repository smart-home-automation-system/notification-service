package cloud.cholewa.notification.household.sms;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * The SMS of the alarm of a garage gate (HAS-472, owner 2026-11-18): {@code sms-members} - the members of the
 * household registry, by name and whatever the case, who are texted the first message of an alarm, at home or not.
 * The names come from the manifest, never from this repository; none set is no SMS of a garage alarm (a WARN).
 */
@ConfigurationProperties(prefix = "household.garage")
public record GarageProperties(List<String> smsMembers) {

    public GarageProperties {
        smsMembers = smsMembers == null ? List.of() : List.copyOf(smsMembers);
    }
}
