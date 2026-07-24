package cloud.cholewa.notification.infrastructure.error.processor;

import cloud.cholewa.commons.error.model.ErrorMessage;
import cloud.cholewa.commons.error.model.Errors;
import cloud.cholewa.commons.error.processor.ExceptionProcessor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;

import java.util.Collections;

@Slf4j
public class NotificationExceptionProcessor implements ExceptionProcessor {

    @Override
    public Errors apply(final Throwable throwable) {
        log.error("Error while sending notification: {}", throwable.getMessage());
        
        return Errors.builder()
            .httpStatus(HttpStatus.BAD_REQUEST)
            .errors(Collections.singleton(
                ErrorMessage.builder().message(throwable.getMessage()).build()
            ))
            .build();
    }
}
