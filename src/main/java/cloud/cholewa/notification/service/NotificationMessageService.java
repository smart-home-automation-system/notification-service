package cloud.cholewa.notification.service;

import cloud.cholewa.notification.discord.skippy.service.DiscordBotService;
import discord4j.rest.http.client.ClientException;
import io.netty.channel.ChannelException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationMessageService {

    static final long DELIVERY_RETRY_ATTEMPTS = 4;
    static final Duration DELIVERY_RETRY_BACKOFF = Duration.ofSeconds(5);

    private static final int NOT_FOUND = 404;
    private static final int SERVER_ERROR = 500;

    private final DiscordBotService discordBotService;

    /**
     * Delivers the message to Discord. A failed delivery is retried here, with a backoff, and the
     * error is signalled only when the retries are used up: handing the message back to the
     * broker instead would redeliver it at once, in a loop, for as long as Discord stays down.
     */
    public Mono<Void> processMessage(final String message) {
        return discordBotService.sendMessage(message)
            .retryWhen(Retry.backoff(DELIVERY_RETRY_ATTEMPTS, DELIVERY_RETRY_BACKOFF)
                .filter(NotificationMessageService::isWorthRetrying)
                .doBeforeRetry(signal -> log.warn(
                    "Delivery to Discord failed, attempt {} of {}: {}",
                    signal.totalRetries() + 1,
                    DELIVERY_RETRY_ATTEMPTS + 1,
                    signal.failure().getMessage()
                ))
                //the last failure itself, not "Retries exhausted" with the cause hidden behind it
                .onRetryExhaustedThrow((spec, signal) -> signal.failure()));
    }

    //only what another attempt can change: Discord failing on its side, or the network. A missing
    //channel, a rejected token, a message Discord refuses or a fault in this service will fail
    //the same way every time. The one 4xx worth another attempt is 404: the channel is looked up
    //again then. Rate limits (429) never get here, discord4j waits them out itself
    private static boolean isWorthRetrying(final Throwable throwable) {
        if (throwable instanceof ClientException clientException) {
            final int status = clientException.getStatus().code();

            return status >= SERVER_ERROR || status == NOT_FOUND;
        }
        //the network failure is often the cause, wrapped by the client that reports it
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            if (cause instanceof IOException
                || cause instanceof TimeoutException
                //netty's read and write timeouts are neither of the two above
                || cause instanceof ChannelException) {
                return true;
            }
        }
        return false;
    }
}
