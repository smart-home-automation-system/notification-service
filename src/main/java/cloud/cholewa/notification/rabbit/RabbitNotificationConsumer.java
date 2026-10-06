package cloud.cholewa.notification.rabbit;

import cloud.cholewa.notification.model.NotificationLevel;
import cloud.cholewa.notification.service.NotificationMessageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Slf4j
@Service
public class RabbitNotificationConsumer {

    static final String LEVEL_HEADER = "level";

    private final NotificationMessageService notificationMessageService;
    private final String infoQueue;

    RabbitNotificationConsumer(
        final NotificationMessageService notificationMessageService,
        @Value("${rabbit.info.queue}") final String infoQueue
    ) {
        this.notificationMessageService = notificationMessageService;
        this.infoQueue = infoQueue;
    }

    //one listener for both queues: the queue a message came from only decides the level it gets
    //when its publisher names none in the level header
    @RabbitListener(queues = {"${rabbit.alert.queue}", "${rabbit.info.queue}"})
    Mono<Void> consumeMessage(
        @Payload final String message,
        @Header(name = AmqpHeaders.CONSUMER_QUEUE, required = false) final String queue,
        @Header(name = LEVEL_HEADER, required = false) final String level
    ) {
        return notificationMessageService
            .processMessage(NotificationLevel.of(level, defaultLevel(queue)), message)
            .doOnSubscribe(subscription -> log.info("Received message from {}: {}", queue, message))
            //the delivery was already retried; an error signal here would make the container hand
            //the message back to the broker and receive it again at once. The text goes into the
            //log, because from this point the log is the only place the notification still exists
            .onErrorResume(throwable -> {
                log.error("Message from {} not delivered: {} - {}", queue, message, throwable.getMessage());
                return Mono.empty();
            })
            //Spring AMQP subscribes without triggering the automatic ThreadLocal capture and,
            //unlike the HTTP path, never writes the observation into the reactor context itself,
            //so without this the traceId stays on the container thread and everything the message
            //triggers is logged without it
            .contextCapture();
    }

    //an info is an INFO and an alert an ERROR. Anything else - the container always names the
    //queue, so this is a queue added to the listener without a level of its own - is shown as an
    //ERROR too: the wrong color should be the one that gets looked at
    private NotificationLevel defaultLevel(final String queue) {
        return infoQueue.equals(queue) ? NotificationLevel.INFO : NotificationLevel.ERROR;
    }
}
