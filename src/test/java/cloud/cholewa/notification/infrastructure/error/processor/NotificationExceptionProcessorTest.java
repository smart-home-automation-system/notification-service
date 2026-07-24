package cloud.cholewa.notification.infrastructure.error.processor;

import cloud.cholewa.commons.error.model.ErrorMessage;
import cloud.cholewa.commons.error.model.Errors;
import cloud.cholewa.notification.infrastructure.error.NotificationException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationExceptionProcessorTest {

    private final NotificationExceptionProcessor sut = new NotificationExceptionProcessor();

    @Test
    void should_map_notification_exception_to_bad_request_with_message() {
        final Errors errors = sut.apply(new NotificationException("channel unavailable"));

        assertThat(errors.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errors.getErrors())
            .extracting(ErrorMessage::getMessage)
            .containsExactly("channel unavailable");
    }
}
