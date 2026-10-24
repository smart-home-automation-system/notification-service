package cloud.cholewa.notification.household;

import cloud.cholewa.home.model.HouseholdNotification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Delivers a household notification by every transport at once (HAS-274). The transports are
 * independent, and one retries for over a minute when its service is down (Discord: four retries
 * from 5 s) - in series that would hold back the SMS and the push of every message behind it. A
 * transport that fails never fails the others: its error is logged at ERROR with the transport
 * named. The returned Mono completes once every transport finished, so the listener acknowledges
 * the message only then - and never because of one transport hands it back to the broker, which
 * would deliver it again by the transports that did succeed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HouseholdDispatcher {

    private final List<HouseholdTransport> transports;

    public Mono<Void> dispatch(final HouseholdNotification notification) {
        //deferred: the words are composed here, and a fault in them has to be a signal
        return Mono.fromCallable(() -> HouseholdMessages.of(notification))
            .flatMapMany(message -> Flux.fromIterable(transports)
                .flatMap(transport -> Mono.defer(() -> transport.deliver(notification, message))
                    .doOnSuccess(done -> log.info(
                        "Household notification [{}] of [{}] delivered by [{}]",
                        notification.getEvent(), notification.getSubject(), transport.name()))
                    .onErrorResume(throwable -> {
                        log.error(
                            "Household notification [{}] of [{}] not delivered by [{}]: {} - {}",
                            notification.getEvent(),
                            notification.getSubject(),
                            transport.name(),
                            throwable.getClass().getSimpleName(),
                            throwable.getMessage()
                        );
                        return Mono.empty();
                    })))
            .then();
    }
}
