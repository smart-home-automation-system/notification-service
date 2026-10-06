package cloud.cholewa.notification.rabbit;

import cloud.cholewa.notification.model.NotificationLevel;
import cloud.cholewa.notification.service.NotificationMessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

@Slf4j
@Service
@RequiredArgsConstructor
public class RabbitNotificationConsumer {

    static final String LEVEL_HEADER = "level";

    private final NotificationMessageService notificationMessageService;

    //an alert is an ERROR unless its publisher says otherwise in the level header
    @RabbitListener(queues = "${rabbit.alert.queue}")
    Mono<Void> consumeAlertMessage(
        @Payload final String message,
        @Header(name = LEVEL_HEADER, required = false) final String level
    ) {
        return consume("alert", NotificationLevel.ERROR, message, level);
    }

    //an info is an INFO unless its publisher says otherwise in the level header
    @RabbitListener(queues = "${rabbit.info.queue}")
    Mono<Void> consumeInfoMessage(
        @Payload final String message,
        @Header(name = LEVEL_HEADER, required = false) final String level
    ) {
        return consume("info", NotificationLevel.INFO, message, level);
    }

    //two listeners on purpose, each with a container of its own: a queue that is missing stays a
    //failure of its listener, and what happens to the channel of one queue never redelivers the
    //messages of the other
    private Mono<Void> consume(
        final String category,
        final NotificationLevel defaultLevel,
        final String message,
        final String level
    ) {
        //deferred, so that an exception thrown while the delivery is put together ends in the
        //onErrorResume below like any other, instead of leaving the listener method
        return Mono.defer(() -> notificationMessageService
                .processMessage(NotificationLevel.of(level, defaultLevel), message))
            .doOnSubscribe(subscription -> log.info("Received {} message: {}", category, message))
            //the delivery was already retried; an error signal here would make the container hand
            //the message back to the broker and receive it again at once. The text goes into the
            //log, because from this point the log is the only place the notification still exists
            .onErrorResume(throwable -> {
                log.error(
                    "{} message not delivered: {} - {}",
                    StringUtils.capitalize(category),
                    message,
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
}
