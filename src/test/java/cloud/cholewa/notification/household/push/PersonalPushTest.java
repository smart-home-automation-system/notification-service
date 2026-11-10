package cloud.cholewa.notification.household.push;

import cloud.cholewa.home.model.PushSubscription;
import cloud.cholewa.home.model.PushSubscriptionKeys;
import cloud.cholewa.notification.household.PersonalMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

//A push for one member (HAS-416): every browser of that member, nobody asked whether at home; what it came to
//told apart - delivered, nothing to post to, failed
class PersonalPushTest {

    private static final String APPLE = "https://web.push.apple.com/QGuQyavXutnMH";
    private static final String GOOGLE = "https://fcm.googleapis.com/fcm/send/abc";
    private static final PersonalMessage REMINDER = new PersonalMessage("Czy zrobione?", "chleb (Borys)",
        "Czy zrobione? Odhacz w aplikacji: chleb (Borys)", "requests-reminder", "/room", true);

    private final PushSubscriptionsClient subscriptions = mock(PushSubscriptionsClient.class);
    private final WebPushSender sender = mock(WebPushSender.class);
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private PersonalPush sut;

    private static PushSubscription subscription(final String endpoint) {
        return PushSubscription.builder()
            .endpoint(endpoint)
            .keys(PushSubscriptionKeys.builder().p256dh("BAAA").auth("AAAA").build())
            .build();
    }

    @BeforeEach
    void setUp() {
        sut = new PersonalPush(subscriptions, sender, List.of("push.apple.com", "fcm.googleapis.com"), jsonMapper);
    }

    @Test
    void should_push_to_the_browsers_of_the_member_with_its_own_tag_and_page() {
        when(subscriptions.ofMembers(List.of("Liwia"))).thenReturn(Mono.just(List.of(subscription(APPLE), subscription(GOOGLE))));
        when(sender.send(any(), any(), any())).thenReturn(Mono.just(WebPushSender.Outcome.DELIVERED),
            Mono.just(WebPushSender.Outcome.FAILED));

        sut.send("Liwia", REMINDER).as(StepVerifier::create).expectNext(PersonalPush.Result.DELIVERED).verifyComplete();

        final ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(sender, times(2)).send(any(), any(), payload.capture());
        final JsonNode shown = jsonMapper.readTree(payload.getValue()).get("notification");
        assertThat(shown.get("title").asString()).isEqualTo("Czy zrobione?");
        assertThat(shown.get("body").asString()).isEqualTo("chleb (Borys)");
        assertThat(shown.get("tag").asString()).isEqualTo("requests-reminder");
        assertThat(shown.get("requireInteraction").asBoolean()).isTrue();
        assertThat(shown.get("data").get("onActionClick").get("default").get("url").asString()).isEqualTo("/room");
    }

    //none subscribed, or none on an allowed push service: nothing to post to
    @Test
    void should_answer_no_subscription_when_there_is_nothing_to_post_to() {
        when(subscriptions.ofMembers(List.of("Liwia"))).thenReturn(Mono.just(List.of()),
            Mono.just(List.of(subscription("https://push.example.com/x"))));

        sut.send("Liwia", REMINDER).as(StepVerifier::create).expectNext(PersonalPush.Result.NO_SUBSCRIPTION).verifyComplete();
        sut.send("Liwia", REMINDER).as(StepVerifier::create).expectNext(PersonalPush.Result.NO_SUBSCRIPTION).verifyComplete();
        verifyNoInteractions(sender);
    }

    //a name the read cannot ask for counts as no subscription
    @Test
    void should_not_ask_for_a_name_with_a_comma() {
        sut.send("Liwia, Borys", REMINDER).as(StepVerifier::create).expectNext(PersonalPush.Result.NO_SUBSCRIPTION).verifyComplete();
        verifyNoInteractions(subscriptions);
    }

    @Test
    void should_fail_when_no_browser_took_it_or_the_read_failed() {
        when(subscriptions.ofMembers(List.of("Liwia"))).thenReturn(Mono.just(List.of(subscription(APPLE))),
            Mono.error(new IllegalStateException("database-service answered 503")));
        when(sender.send(any(), any(), any())).thenReturn(Mono.just(WebPushSender.Outcome.FAILED));

        sut.send("Liwia", REMINDER).as(StepVerifier::create).expectNext(PersonalPush.Result.FAILED).verifyComplete();
        sut.send("Liwia", REMINDER).as(StepVerifier::create).expectNext(PersonalPush.Result.FAILED).verifyComplete();
    }

    //a subscription the push service dropped is removed, and is no failure
    @Test
    void should_remove_a_subscription_that_is_gone() {
        when(subscriptions.ofMembers(List.of("Liwia"))).thenReturn(Mono.just(List.of(subscription(APPLE))));
        when(sender.send(any(), any(), any())).thenReturn(Mono.just(WebPushSender.Outcome.GONE));
        when(subscriptions.remove(APPLE)).thenReturn(Mono.empty());

        sut.send("Liwia", REMINDER).as(StepVerifier::create).expectNext(PersonalPush.Result.NO_SUBSCRIPTION).verifyComplete();
        verify(subscriptions).remove(eq(APPLE));
    }
}
