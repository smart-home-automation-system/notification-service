package cloud.cholewa.notification.infrastructure.error;

public class NotificationException extends RuntimeException {
    
    public NotificationException(final String message) {
        super(message);
    }
}
