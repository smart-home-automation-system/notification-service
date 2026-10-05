package cloud.cholewa.notification.rabbit;

import cloud.cholewa.notification.model.NotificationLevel;
import cloud.cholewa.notification.service.NotificationMessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Slf4j
@Service
@RequiredArgsConstructor
public class RabbitAlertMessageConsumer {

    static final String LEVEL_HEADER = "level";

    private final NotificationMessageService notificationMessageService;

    //an alert is an ERROR unless its publisher says otherwise in the level header
    @RabbitListener(queues = "${rabbit.alert.queue}")
    Mono<Void> consumeAlertMessage(
        @Payload final String message,
        @Header(name = LEVEL_HEADER, required = false) final String level
    ) {
        return notificationMessageService
            .processMessage(NotificationLevel.of(level, NotificationLevel.ERROR), message)
            .doOnSubscribe(subscription -> log.info("Received alert message: {}", message))
            //the delivery was already retried; an error signal here would make the container hand
            //the message back to the broker and receive it again at once. The text goes into the
            //log, because from this point the log is the only place the alert still exists
            .onErrorResume(throwable -> {
                log.error("Alert message not delivered: {} - {}", message, throwable.getMessage());
                return Mono.empty();
            })
            //Spring AMQP subscribes without triggering the automatic ThreadLocal capture and,
            //unlike the HTTP path, never writes the observation into the reactor context itself,
            //so without this the traceId stays on the container thread and everything the message
            //triggers is logged without it
            .contextCapture();
    }
}
