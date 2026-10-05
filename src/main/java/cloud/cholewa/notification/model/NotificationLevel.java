package cloud.cholewa.notification.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * How a notification is shown on Discord: the name is the title of the embed, the color its bar
 * on the left. Publishers name the level in the {@code level} message header.
 */
@Getter
@RequiredArgsConstructor
public enum NotificationLevel {

    ERROR(0xE74C3C),
    WARN(0xF1C40F),
    INFO(0x2ECC71);

    private final int color;

    /**
     * The level named by a publisher, in any case. A missing or unknown name gives the fallback:
     * a notification is worth more with the wrong color than not delivered at all.
     */
    public static NotificationLevel of(final String name, final NotificationLevel fallback) {
        if (name != null) {
            for (final NotificationLevel level : values()) {
                if (level.name().equalsIgnoreCase(name.strip())) {
                    return level;
                }
            }
        }
        return fallback;
    }
}
