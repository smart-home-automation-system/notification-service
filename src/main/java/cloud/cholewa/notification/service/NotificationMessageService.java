package cloud.cholewa.notification.service;

import cloud.cholewa.notification.discord.skippy.service.DiscordBotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationMessageService {

    static final long DELIVERY_RETRY_ATTEMPTS = 4;
    static final Duration DELIVERY_RETRY_BACKOFF = Duration.ofSeconds(5);

    private final DiscordBotService discordBotService;

    /**
     * Delivers the message to Discord. A failed delivery is retried here, with a backoff, and the
     * error is signalled only when the retries are used up: handing the message back to the
     * broker instead would redeliver it at once, in a loop, for as long as Discord stays down.
     */
    public Mono<Void> processMessage(final String message) {
        return discordBotService.sendMessage(message)
            .retryWhen(Retry.backoff(DELIVERY_RETRY_ATTEMPTS, DELIVERY_RETRY_BACKOFF)
                .doBeforeRetry(signal -> log.warn(
                    "Delivery to Discord failed, attempt {} of {}: {}",
                    signal.totalRetries() + 1,
                    DELIVERY_RETRY_ATTEMPTS + 1,
                    signal.failure().getMessage()
                ))
                //the last failure itself, not "Retries exhausted" with the cause hidden behind it
                .onRetryExhaustedThrow((spec, signal) -> signal.failure()));
    }
}
