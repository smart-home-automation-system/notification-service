package cloud.cholewa.notification.household;

/**
 * A message for one member of the household (HAS-416): the reminders and the announcements of the household
 * requests, delivered by push to that member's browsers or, without one that takes it, by SMS. In Polish,
 * composed by the sender.
 *
 * @param title  the title of the push notification
 * @param text   its body
 * @param sms    the same in one line, for the SMS
 * @param tag    a newer push of the same tag replaces the older one on the phone
 * @param path   the page of the application a tap opens
 * @param sticky the push stays on the screen until it is clicked - for a message asking for something
 */
public record PersonalMessage(String title, String text, String sms, String tag, String path, boolean sticky) {
}
