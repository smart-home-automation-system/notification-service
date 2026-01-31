package cloud.cholewa.notification.rabbit;

import cloud.cholewa.notification.service.AlertMessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Slf4j
@Service
@RequiredArgsConstructor
public class RabbitAlertMessageConsumer {

    private final AlertMessageService alertMessageService;

    @RabbitListener(queues = "${rabbit.alert.queue}")
    Mono<Void> consumeAlertMessage(final String message) {
        return alertMessageService.processMessage(message)
            .doOnSubscribe(subscription -> log.info("Received alert message: {}", message))
            .onErrorResume(throwable -> {
                log.error("Error while consuming alert message: {}", throwable.getMessage());
                return Mono.empty();
            });
    }
}
