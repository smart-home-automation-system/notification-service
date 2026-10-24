package cloud.cholewa.notification.household;

//a household payload that cannot be worded: not the JSON of HouseholdNotification, or out of its bounds
public class InvalidHouseholdNotificationException extends RuntimeException {

    public InvalidHouseholdNotificationException(final String message) {
        super(message);
    }
}
