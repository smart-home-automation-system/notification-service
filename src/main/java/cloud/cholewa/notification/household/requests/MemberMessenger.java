package cloud.cholewa.notification.household.requests;

import cloud.cholewa.notification.household.PersonalMessage;
import cloud.cholewa.notification.household.members.HouseholdRegistryClient.Member;
import cloud.cholewa.notification.household.push.PersonalPush;
import cloud.cholewa.notification.household.sms.SmsApiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * A message for one member of the household (HAS-416, owner 2026-11-10): Web Push to that member's browsers,
 * at home or not; an SMS only when no browser took it - none subscribed, or all failed - so nobody is left
 * without the message. Never an error:
 * <ul>
 *   <li>{@code PUSH} / {@code SMS} - delivered that way;</li>
 *   <li>{@code UNREACHABLE} - no browser to post to and no phone: nothing any later attempt can change;</li>
 *   <li>{@code FAILED} - it could have gone and did not (a push failed with no phone to fall back on, an SMS
 *   refused, the SMS switched off): another attempt may deliver it.</li>
 * </ul>
 * The push or the SMS switched off is no push or no SMS; the text is never logged.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class MemberMessenger {

    enum Delivery { PUSH, SMS, UNREACHABLE, FAILED }

    private final ObjectProvider<PersonalPush> personalPush;
    private final ObjectProvider<SmsApiClient> smsApiClient;

    Mono<Delivery> deliver(final Member member, final PersonalMessage message) {
        return Mono.justOrEmpty(personalPush.getIfAvailable())
            .flatMap(push -> push.send(member.name(), message))
            .defaultIfEmpty(PersonalPush.Result.NO_SUBSCRIPTION)
            .flatMap(pushed -> Mono.just(pushed)
                .filter(PersonalPush.Result.DELIVERED::equals)
                .map(delivered -> Delivery.PUSH)
                .switchIfEmpty(Mono.defer(() -> bySms(member, message, pushed))));
    }

    private Mono<Delivery> bySms(final Member member, final PersonalMessage message, final PersonalPush.Result pushed) {
        return Mono.justOrEmpty(member.phone())
            .filter(phone -> !phone.isBlank())
            .flatMap(phone -> Mono.justOrEmpty(smsApiClient.getIfAvailable())
                .switchIfEmpty(Mono.fromRunnable(() -> log.warn(
                    "A message for [{}] was not texted: the SMS is switched off (household.sms.enabled)", member.name())))
                .flatMap(client -> client.send(member.name(), phone, message.sms()))
                .map(accepted -> Boolean.TRUE.equals(accepted) ? Delivery.SMS : Delivery.FAILED)
                .defaultIfEmpty(Delivery.FAILED))
            //no phone: unreachable, unless a push could have gone and failed - then the next attempt may deliver it
            .defaultIfEmpty(pushed == PersonalPush.Result.FAILED ? Delivery.FAILED : Delivery.UNREACHABLE);
    }
}
