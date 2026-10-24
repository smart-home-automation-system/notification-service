package cloud.cholewa.notification.household;

/**
 * A household notification as the people of the house read it - composed here from the event,
 * never carried in the payload. {@code title} is the device, {@code text} the Polish sentence,
 * {@code footer} the time of the house, {@code color} the bar of the Discord embed. The SMS and
 * the Web Push to come (HAS-275, HAS-276) word the same message, each in their own length.
 */
public record HouseholdMessage(String title, String text, String footer, int color) {
}
