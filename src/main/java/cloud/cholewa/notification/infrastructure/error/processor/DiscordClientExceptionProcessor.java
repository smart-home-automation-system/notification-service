package cloud.cholewa.notification.infrastructure.error.processor;

import cloud.cholewa.commons.error.model.ErrorMessage;
import cloud.cholewa.commons.error.model.Errors;
import cloud.cholewa.commons.error.processor.ExceptionProcessor;
import discord4j.rest.http.client.ClientException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;

import java.util.Collections;

@Slf4j
public class DiscordClientExceptionProcessor implements ExceptionProcessor {

    //only the status goes out and into the log: the exception message carries the request and
    //Discord's whole reply, and the default processor would pass it on as details
    @Override
    public Errors apply(final Throwable throwable) {
        final String message = "Discord refused the request with status: "
            + ((ClientException) throwable).getStatus().code();

        log.error("Error while sending notification: {}", message);

        return Errors.builder()
            .httpStatus(HttpStatus.BAD_GATEWAY)
            .errors(Collections.singleton(
                ErrorMessage.builder().message(message).build()
            ))
            .build();
    }
}
