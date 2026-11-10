package cloud.cholewa.notification.household.requests;

import cloud.cholewa.notification.household.PersonalMessage;
import cloud.cholewa.notification.household.members.HouseholdRegistryClient.Member;
import cloud.cholewa.notification.household.push.PersonalPush;
import cloud.cholewa.notification.household.sms.SmsApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//A message for one member (HAS-416): push first, an SMS only when no browser took it, and what nothing can change
//told apart from what another attempt may deliver
class MemberMessengerTest {

    private static final PersonalMessage MESSAGE = new PersonalMessage("Zrobione", "chleb (Liwia)",
        "Zrobione: chleb (Liwia)", "requests-done", "/room", false);
    private static final Member WITH_PHONE = new Member("Borys", "+48500000002", true, "resident");
    private static final Member WITHOUT_PHONE = new Member("Ewa", null, true, "resident");

    private final PersonalPush push = mock(PersonalPush.class);
    private final SmsApiClient sms = mock(SmsApiClient.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<PersonalPush> pushProvider = mock(ObjectProvider.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<SmsApiClient> smsProvider = mock(ObjectProvider.class);
    private MemberMessenger sut;

    @BeforeEach
    void setUp() {
        when(pushProvider.getIfAvailable()).thenReturn(push);
        when(smsProvider.getIfAvailable()).thenReturn(sms);
        when(sms.send(anyString(), anyString(), anyString())).thenReturn(Mono.just(true));
        sut = new MemberMessenger(pushProvider, smsProvider);
    }

    @Test
    void should_push_and_text_nothing_when_a_browser_took_it() {
        when(push.send("Borys", MESSAGE)).thenReturn(Mono.just(PersonalPush.Result.DELIVERED));

        sut.deliver(WITH_PHONE, MESSAGE).as(StepVerifier::create).expectNext(MemberMessenger.Delivery.PUSH).verifyComplete();
        verify(sms, never()).send(anyString(), anyString(), anyString());
    }

    //nobody is left without it: no subscription, or every browser failed, and a phone - an SMS
    @Test
    void should_text_the_one_line_when_no_browser_took_it() {
        when(push.send(anyString(), any())).thenReturn(Mono.just(PersonalPush.Result.NO_SUBSCRIPTION),
            Mono.just(PersonalPush.Result.FAILED));

        sut.deliver(WITH_PHONE, MESSAGE).as(StepVerifier::create).expectNext(MemberMessenger.Delivery.SMS).verifyComplete();
        sut.deliver(WITH_PHONE, MESSAGE).as(StepVerifier::create).expectNext(MemberMessenger.Delivery.SMS).verifyComplete();
        verify(sms, times(2)).send("Borys", "+48500000002", "Zrobione: chleb (Liwia)");
    }

    @Test
    void should_text_when_the_push_is_switched_off() {
        when(pushProvider.getIfAvailable()).thenReturn(null);

        sut.deliver(WITH_PHONE, MESSAGE).as(StepVerifier::create).expectNext(MemberMessenger.Delivery.SMS).verifyComplete();
    }

    //no browser and no phone: nothing later changes it; a push that failed with no phone may go next time
    @Test
    void should_tell_unreachable_from_failed_without_a_phone() {
        when(push.send(anyString(), any())).thenReturn(Mono.just(PersonalPush.Result.NO_SUBSCRIPTION),
            Mono.just(PersonalPush.Result.FAILED));

        sut.deliver(WITHOUT_PHONE, MESSAGE).as(StepVerifier::create)
            .expectNext(MemberMessenger.Delivery.UNREACHABLE).verifyComplete();
        sut.deliver(WITHOUT_PHONE, MESSAGE).as(StepVerifier::create)
            .expectNext(MemberMessenger.Delivery.FAILED).verifyComplete();
        verify(sms, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void should_fail_when_the_sms_is_refused_or_switched_off() {
        when(push.send(anyString(), any())).thenReturn(Mono.just(PersonalPush.Result.NO_SUBSCRIPTION));
        when(sms.send(anyString(), anyString(), anyString())).thenReturn(Mono.just(false));

        sut.deliver(WITH_PHONE, MESSAGE).as(StepVerifier::create).expectNext(MemberMessenger.Delivery.FAILED).verifyComplete();
        when(smsProvider.getIfAvailable()).thenReturn(null);
        sut.deliver(WITH_PHONE, MESSAGE).as(StepVerifier::create).expectNext(MemberMessenger.Delivery.FAILED).verifyComplete();
    }
}
