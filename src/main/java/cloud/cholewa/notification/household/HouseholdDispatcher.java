package cloud.cholewa.notification.household;

import cloud.cholewa.home.model.HouseholdNotification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Delivers a household notification by every transport, one after the other (HAS-274). A
 * transport that fails never fails the others: its error is logged at ERROR with the transport
 * named, and the next one is tried. The returned Mono completes once every transport was tried,
 * so the listener acknowledges the message only then - and never because of one transport hands
 * it back to the broker, which would deliver it again by the transports that did succeed.
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
                .concatMap(transport -> Mono.defer(() -> transport.deliver(notification, message))
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
