package cloud.cholewa.notification.household;

import cloud.cholewa.home.model.HouseholdNotification;
import reactor.core.publisher.Mono;

/**
 * One way a household notification reaches the people of the house: the Discord channel, the Web Push
 * (HAS-276) and the SMS to come (HAS-275). A transport retries what another attempt
 * can change itself and signals an error when it gave up; the dispatcher keeps that error from
 * the other transports.
 */
public interface HouseholdTransport {

    //how the transport is named in the log - the one line that says which way did not get through
    String name();

    Mono<Void> deliver(HouseholdNotification notification, HouseholdMessage message);
}
