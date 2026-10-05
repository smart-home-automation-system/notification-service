package cloud.cholewa.notification.rabbit;

import cloud.cholewa.notification.service.NotificationMessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Slf4j
@Service
@RequiredArgsConstructor
public class RabbitInfoMessageConsumer {

    private final NotificationMessageService notificationMessageService;

    @RabbitListener(queues = "${rabbit.info.queue}")
    Mono<Void> consumeInfoMessage(final String message) {
        return notificationMessageService.processMessage(message)
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
