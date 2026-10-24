package cloud.cholewa.notification.service;

import discord4j.rest.http.client.ClientException;
import io.netty.channel.ChannelException;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.netty.channel.AbortedException;
import reactor.util.retry.Retry;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

/**
 * How a delivery to Discord is retried - the alerts and infos, and the household channel alike.
 * The error is signalled only when the retries are used up: handing the message back to the
 * broker instead would redeliver it at once, in a loop, for as long as Discord stays down.
 */
@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class DiscordDeliveryRetry {

    public static final long ATTEMPTS = 4;
    public static final Duration BACKOFF = Duration.ofSeconds(5);

    private static final int SERVER_ERROR = 500;

    public static Retry spec() {
        return Retry.backoff(ATTEMPTS, BACKOFF)
            .filter(DiscordDeliveryRetry::isWorthRetrying)
            .doBeforeRetry(signal -> log.warn(
                "Delivery to Discord failed, attempt {} of {}: {}",
                signal.totalRetries() + 1,
                ATTEMPTS + 1,
                signal.failure().getMessage()
            ))
            //the last failure itself, not "Retries exhausted" with the cause hidden behind it
            .onRetryExhaustedThrow((spec, signal) -> signal.failure());
    }

    //only what another attempt can change: Discord failing on its side, or the network. A wrong
    //channel id, a rejected token, a message Discord refuses or a fault in this service will fail
    //the same way every time. Rate limits (429) never get here, discord4j waits them out itself
    private static boolean isWorthRetrying(final Throwable throwable) {
        //down the causes: the client wraps what it reports - a 5xx arrives inside the
        //"retries exhausted" of discord4j's own attempts, a network failure inside whatever
        //noticed it
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            if (cause instanceof ClientException clientException) {
                return clientException.getStatus().code() >= SERVER_ERROR;
            }
            if (cause instanceof IOException
                || cause instanceof TimeoutException
                //netty's read and write timeouts are neither of the two above
                || cause instanceof ChannelException
                //a pooled connection the peer closed while it was idle; a RuntimeException
                || cause instanceof AbortedException) {
                return true;
            }
        }
        return false;
    }
}
