package cloud.cholewa.notification.household;

import cloud.cholewa.home.model.HouseholdNotification;
import cloud.cholewa.home.model.RoomName;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

import java.util.Comparator;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The JSON payload of the household queue as the SDK model, checked against the bounds of the
 * model. Unlike the plain text of alert and info, a household message is a structure the words
 * are composed from - one that is not the model, or breaks its bounds, cannot be worded and is
 * refused as a whole. The mapper is the one of Spring Boot, so a property the model does not know
 * (from a newer publisher) is ignored, not refused.
 * <p>
 * **A room this version does not know is read as no room** (HAS-462): the room only places a fire alarm, and
 * notification-service reads no registry, so it is not among the services that move to a new SDK first - a room added
 * there and published by safety-service before this service bumps must not drop the alarm. It is worded without its
 * room, and the unknown value is a WARN.
 */
@Slf4j
@Component
public class HouseholdNotificationReader {

    private final JsonMapper jsonMapper;
    private final Validator validator;

    public HouseholdNotificationReader(final JsonMapper jsonMapper, final Validator validator) {
        this.jsonMapper = jsonMapper.rebuild()
            .addModule(new SimpleModule("tolerant-room").addDeserializer(RoomName.class, new TolerantRoomName()))
            .build();
        this.validator = validator;
    }

    //RoomName.fromValue refuses a value it does not know: here it is no room instead
    private static final class TolerantRoomName extends ValueDeserializer<RoomName> {

        @Override
        public RoomName deserialize(final JsonParser parser, final DeserializationContext context) {
            //an object or an array is no room: skipped whole, so the properties after it are still read
            if (parser.currentToken().isStructStart()) {
                parser.skipChildren();
                log.warn("A room that is not a text, read as no room");
                return null;
            }
            final String value = parser.getValueAsString();
            try {
                return RoomName.fromValue(value);
            } catch (IllegalArgumentException e) {
                log.warn("A room this version does not know, read as no room: [{}]", value);
                return null;
            }
        }
    }

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
