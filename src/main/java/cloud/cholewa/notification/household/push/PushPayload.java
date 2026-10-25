package cloud.cholewa.notification.household.push;

import cloud.cholewa.home.model.HouseholdEvent;
import cloud.cholewa.home.model.HouseholdNotification;
import cloud.cholewa.notification.household.HouseholdMessage;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the installed application receives (HAS-276), in the shape the service worker of Angular shows
 * by itself: {@code {"notification": {title, body, tag, data}}} - the worker calls {@code showNotification}
 * with it and, on a tap, opens {@code data.onActionClick.default.url} (the page where the household
 * acknowledges, or the start page). The application (HAS-284) therefore needs no worker of its own.
 * <p>
 * {@code tag} is {@code appliance-<deviceId>}: a reminder replaces the notification of the same appliance
 * on the phone instead of stacking under it, and so does the acknowledgement. The words are those of
 * the Discord message - one composition, {@link cloud.cholewa.notification.household.HouseholdMessages}.
 */
final class PushPayload {

    static final String START_PAGE = "/";

    private PushPayload() {
    }

    static String tagOf(final HouseholdNotification notification) {
        return "appliance-" + notification.getDeviceId();
    }

    static byte[] of(final HouseholdNotification notification, final HouseholdMessage message, final JsonMapper jsonMapper) {
        final String path = notification.getAcknowledgePath() == null ? START_PAGE : notification.getAcknowledgePath();

        final Map<String, Object> data = new LinkedHashMap<>();
        data.put("path", path);
        data.put("onActionClick", Map.of("default", Map.of("operation", "navigateLastFocusedOrOpen", "url", path)));

        final Map<String, Object> shown = new LinkedHashMap<>();
        shown.put("title", message.title());
        shown.put("body", message.text());
        shown.put("tag", tagOf(notification));
        //a reminder that replaces the notification sounds again - it is meant to; the acknowledgement replaces
        //it silently: nobody has to act on it, and it may come late in the evening
        shown.put("renotify", notification.getEvent() != HouseholdEvent.APPLIANCE_ACKNOWLEDGED);
        shown.put("data", data);

        return jsonMapper.writeValueAsBytes(Map.of("notification", shown));
    }
}
