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

/**
 * Two listeners on purpose, each with a container of its own - do not merge them into one on both
 * queues: one container only warns when one of its queues is missing and keeps consuming the
 * other, and what happens to the channel of one queue would redeliver the messages of both.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RabbitNotificationConsumer {

    static final String LEVEL_HEADER = "level";

    private final NotificationMessageService notificationMessageService;

    @RabbitListener(queues = "${rabbit.alert.queue}")
    Mono<Void> consumeAlertMessage(
        @Payload final String message,
        @Header(name = LEVEL_HEADER, required = false) final String level
    ) {
        return consume(Category.ALERT, message, level);
    }

    @RabbitListener(queues = "${rabbit.info.queue}")
    Mono<Void> consumeInfoMessage(
        @Payload final String message,
        @Header(name = LEVEL_HEADER, required = false) final String level
    ) {
        return consume(Category.INFO, message, level);
    }

    private Mono<Void> consume(final Category category, final String message, final String level) {
        //deferred, so that an exception thrown on the way ends in the onErrorResume below like
        //a signalled one, instead of leaving the listener method
        return Mono.defer(() -> notificationMessageService
                .processMessage(NotificationLevel.of(level, category.defaultLevel), message))
            .doOnSubscribe(subscription -> log.info("Received {} message: {}", category.received, message))
            //the delivery was already retried; an error signal here would make the container hand
            //the message back to the broker and receive it again at once. The text goes into the
            //log, because from this point the log is the only place the notification still exists
            .onErrorResume(throwable -> {
                log.error(
                    "{} message not delivered: {} - {}: {}",
                    category.notDelivered,
                    message,
                    throwable.getClass().getSimpleName(),
                    throwable.getMessage()
                );
                return Mono.empty();
            })
            //Spring AMQP subscribes without triggering the automatic ThreadLocal capture and,
            //unlike the HTTP path, never writes the observation into the reactor context itself,
            //so without this the traceId stays on the container thread and everything the message
            //triggers is logged without it
            .contextCapture();
    }

    //what tells the two queues apart: the level of a message whose publisher names none in the
    //level header, and the wording of the log
    @RequiredArgsConstructor
    private enum Category {

        ALERT(NotificationLevel.ERROR, "alert", "Alert"),
        INFO(NotificationLevel.INFO, "info", "Info");

        private final NotificationLevel defaultLevel;
        private final String received;
        private final String notDelivered;
    }
}
