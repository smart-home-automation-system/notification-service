package cloud.cholewa.notification.household;

import cloud.cholewa.home.model.HouseholdNotification;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.Comparator;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The JSON payload of the household queue as the SDK model, checked against the bounds of the
 * model. Unlike the plain text of alert and info, a household message is a structure the words
 * are composed from - one that is not the model, or breaks its bounds, cannot be worded and is
 * refused as a whole. The mapper is the one of Spring Boot, so a property the model does not know
 * (from a newer publisher) is ignored, not refused.
 */
@Component
@RequiredArgsConstructor
public class HouseholdNotificationReader {

    private final JsonMapper jsonMapper;
    private final Validator validator;

    public HouseholdNotification read(final String payload) {
        final HouseholdNotification notification;
        try {
            notification = jsonMapper.readValue(payload, HouseholdNotification.class);
        } catch (JacksonException e) {
            //the reason only: Jackson's message quotes the payload, which the listener logs itself
            throw new InvalidHouseholdNotificationException("not a household notification: " + e.getOriginalMessage());
        }
        if (notification == null) {
            throw new InvalidHouseholdNotificationException("an empty payload");
        }

        final Set<ConstraintViolation<HouseholdNotification>> violations = validator.validate(notification);
        if (!violations.isEmpty()) {
            throw new InvalidHouseholdNotificationException(violations.stream()
                .sorted(Comparator.comparing(violation -> violation.getPropertyPath().toString()))
                .map(violation -> violation.getPropertyPath() + " " + violation.getMessage())
                .collect(Collectors.joining(", ")));
        }
        return notification;
    }
}
