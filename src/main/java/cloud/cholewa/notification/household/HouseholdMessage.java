package cloud.cholewa.notification.household;

/**
 * A household notification as the people of the house read it - composed here from the event,
 * never carried in the payload. {@code title} is the device, {@code text} the Polish sentence,
 * {@code footer} the time of the house, {@code color} the bar of the Discord embed. The Web Push
 * (HAS-276, title and text) and the SMS to come (HAS-275) word the same message.
 */
public record HouseholdMessage(String title, String text, String footer, int color) {

    //the same message with more in its footer - the Discord one names who was texted (HAS-275)
    public HouseholdMessage withFooter(final String more) {
        return new HouseholdMessage(title, text, footer + " · " + more, color);
    }
}
