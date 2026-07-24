package cloud.cholewa.notification.config;

import cloud.cholewa.commons.error.GlobalErrorExceptionHandler;
import cloud.cholewa.notification.infrastructure.error.NotificationException;
import cloud.cholewa.notification.infrastructure.error.processor.NotificationExceptionProcessor;
import org.springframework.boot.autoconfigure.web.WebProperties;
import org.springframework.boot.webflux.error.ErrorAttributes;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.codec.ServerCodecConfigurer;

import java.util.Map;

@Configuration
public class ExceptionHandlerConfig {

    @Bean
    @Order(-2)
    public GlobalErrorExceptionHandler globalExceptionHandler(
        final ErrorAttributes errorAttributes,
        final WebProperties webProperties,
        final ApplicationContext applicationContext,
        final ServerCodecConfigurer serverCodecConfigurer
    ) {
        GlobalErrorExceptionHandler globalErrorExceptionHandler = new GlobalErrorExceptionHandler(
            errorAttributes,
            webProperties.getResources(),
            applicationContext,
            serverCodecConfigurer
        );

        globalErrorExceptionHandler.withCustomErrorProcessor(
            Map.ofEntries(
                Map.entry(NotificationException.class, new NotificationExceptionProcessor())
            )
        );

        return globalErrorExceptionHandler;
    }
}
