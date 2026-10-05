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
public class RabbitInfoMessageConsumer {

    private final NotificationMessageService notificationMessageService;

    //an info is an INFO unless its publisher says otherwise in the level header
    @RabbitListener(queues = "${rabbit.info.queue}")
    Mono<Void> consumeInfoMessage(
        @Payload final String message,
        @Header(name = RabbitAlertMessageConsumer.LEVEL_HEADER, required = false) final String level
    ) {
        return notificationMessageService
            .processMessage(NotificationLevel.of(level, NotificationLevel.INFO), message)
            .doOnSubscribe(subscription -> log.info("Received info message: {}", message))
            //see RabbitAlertMessageConsumer: the delivery was already retried, and an error signal
            //would only start an immediate redelivery loop
            .onErrorResume(throwable -> {
                log.error("Info message not delivered: {} - {}", message, throwable.getMessage());
                return Mono.empty();
            })
            //keeps the traceId on everything the message triggers, see RabbitAlertMessageConsumer
            .contextCapture();
    }
}
