package cloud.cholewa.notification.household.push;

import cloud.cholewa.home.model.HouseholdEvent;
import cloud.cholewa.home.model.HouseholdNotification;
import cloud.cholewa.home.model.PushSubscription;
import cloud.cholewa.home.model.PushSubscriptionKeys;
import cloud.cholewa.notification.household.HouseholdMessage;
import cloud.cholewa.notification.household.HouseholdMessages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WebPushHouseholdTransportTest {

    private static final String APPLE = "https://web.push.apple.com/QGuQyavXutnMH";
    private static final String GOOGLE = "https://fcm.googleapis.com/fcm/send/abc";
    private static final HouseholdNotification FINISHED = HouseholdNotification.builder()
        .event(HouseholdEvent.APPLIANCE_FINISHED)
        .subject("Dishwasher")
        .deviceId(133L)
        .occurredAt(LocalDateTime.of(2026, 10, 25, 14, 45))
        .reminder(0)
        .acknowledgePath("/room")
        .build();
    private static final HouseholdMessage MESSAGE = HouseholdMessages.of(FINISHED);

    private final PresentMembersClient presentMembers = mock(PresentMembersClient.class);
    private final PushSubscriptionsClient subscriptions = mock(PushSubscriptionsClient.class);
    private final WebPushSender sender = mock(WebPushSender.class);
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private WebPushHouseholdTransport sut;

    @BeforeEach
    void setUp() {
        sut = new WebPushHouseholdTransport(presentMembers, subscriptions, sender,
            List.of("push.apple.com", " FCM.googleapis.com "), jsonMapper);
    }

    @Test
    void should_be_named_push_in_the_log() {
        assertThat(sut.name()).isEqualTo("push");
    }

    @Test
    void should_push_to_every_browser_of_the_members_at_home() {
        when(presentMembers.present()).thenReturn(Mono.just(List.of("Ola", "Jan")));
        when(subscriptions.ofMembers(List.of("Ola", "Jan"))).thenReturn(Mono.just(List.of(subscription(APPLE), subscription(GOOGLE))));
        when(sender.send(any(), any(), any(), eq("appliance-133"))).thenReturn(Mono.just(WebPushSender.Outcome.DELIVERED));

        sut.deliver(FINISHED, MESSAGE)
            .as(StepVerifier::create)
            .verifyComplete();

        final ArgumentCaptor<URI> endpoints = ArgumentCaptor.forClass(URI.class);
        verify(sender, org.mockito.Mockito.times(2)).send(endpoints.capture(), any(), any(), eq("appliance-133"));
        assertThat(endpoints.getAllValues()).extracting(URI::toString).containsExactlyInAnyOrder(APPLE, GOOGLE);
    }

    //the payload in the shape the service worker of Angular shows by itself
    @Test
    void should_send_the_words_of_the_household_message_with_the_page_to_open() {
        when(presentMembers.present()).thenReturn(Mono.just(List.of("Ola")));
        when(subscriptions.ofMembers(any())).thenReturn(Mono.just(List.of(subscription(APPLE))));
        when(sender.send(any(), any(), any(), any())).thenReturn(Mono.just(WebPushSender.Outcome.DELIVERED));

        sut.deliver(FINISHED, MESSAGE).as(StepVerifier::create).verifyComplete();

        final ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(sender).send(any(), any(), payload.capture(), any());
        final JsonNode shown = jsonMapper.readTree(payload.getValue()).get("notification");
        assertThat(shown.get("title").asString()).isEqualTo("Dishwasher");
        assertThat(shown.get("body").asString()).isEqualTo("Koniec pracy: Dishwasher - wymaga opróżnienia.");
        assertThat(shown.get("tag").asString()).isEqualTo("appliance-133");
        assertThat(shown.get("renotify").asBoolean()).isTrue();
        assertThat(shown.get("data").get("path").asString()).isEqualTo("/room");
        assertThat(shown.get("data").get("onActionClick").get("default").get("operation").asString())
            .isEqualTo("navigateLastFocusedOrOpen");
        assertThat(shown.get("data").get("onActionClick").get("default").get("url").asString()).isEqualTo("/room");
    }

    @Test
    void should_open_the_start_page_of_a_message_with_nothing_to_acknowledge() {
        final HouseholdNotification acknowledged = HouseholdNotification.builder()
            .event(HouseholdEvent.APPLIANCE_ACKNOWLEDGED).subject("Dishwasher").deviceId(133L)
            .occurredAt(LocalDateTime.of(2026, 10, 25, 15, 20)).reminder(1).build();

        final JsonNode shown = jsonMapper.readTree(PushPayload.of(acknowledged, HouseholdMessages.of(acknowledged), jsonMapper))
            .get("notification");

        assertThat(shown.get("data").get("path").asString()).isEqualTo("/");
    }

    //nobody at home is no failure: the Discord channel has the message
    @Test
    void should_push_nothing_while_nobody_is_at_home() {
        when(presentMembers.present()).thenReturn(Mono.just(List.of()));

        sut.deliver(FINISHED, MESSAGE)
            .as(StepVerifier::create)
            .verifyComplete();

        verifyNoInteractions(subscriptions, sender);
    }

    //never a push to everybody instead
    @Test
    void should_fail_when_it_cannot_learn_who_is_at_home() {
        when(presentMembers.present()).thenReturn(Mono.error(new IllegalStateException("presence-service answered 503")));

        sut.deliver(FINISHED, MESSAGE)
            .as(StepVerifier::create)
            .expectErrorMessage("presence-service answered 503")
            .verify();

        verifyNoInteractions(subscriptions, sender);
    }

    @Test
    void should_leave_out_a_member_whose_name_holds_a_comma() {
        when(presentMembers.present()).thenReturn(Mono.just(List.of("Ola", "Kowalski, Jan")));
        when(subscriptions.ofMembers(List.of("Ola"))).thenReturn(Mono.just(List.of()));

        sut.deliver(FINISHED, MESSAGE)
            .as(StepVerifier::create)
            .verifyComplete();

        verify(subscriptions).ofMembers(List.of("Ola"));
    }

    //the registry takes any https address a browser gives: only the push services are posted to
    @Test
    void should_never_post_to_an_endpoint_outside_the_push_services() {
        when(presentMembers.present()).thenReturn(Mono.just(List.of("Ola")));
        when(subscriptions.ofMembers(any())).thenReturn(Mono.just(List.of(
            subscription("https://evil.example.com/push.apple.com"),
            subscription("https://push.apple.com.evil.example.com/x"),
            subscription("http://web.push.apple.com/plain"),
            subscription(APPLE))));
        when(sender.send(any(), any(), any(), any())).thenReturn(Mono.just(WebPushSender.Outcome.DELIVERED));

        sut.deliver(FINISHED, MESSAGE)
            .as(StepVerifier::create)
            .verifyComplete();

        final ArgumentCaptor<URI> endpoints = ArgumentCaptor.forClass(URI.class);
        verify(sender).send(endpoints.capture(), any(), any(), any());
        assertThat(endpoints.getValue()).hasToString(APPLE);
    }

    @Test
    void should_remove_a_subscription_the_push_service_dropped_and_still_count_the_others() {
        when(presentMembers.present()).thenReturn(Mono.just(List.of("Ola")));
        when(subscriptions.ofMembers(any())).thenReturn(Mono.just(List.of(subscription(APPLE), subscription(GOOGLE))));
        when(sender.send(eq(URI.create(APPLE)), any(), any(), any())).thenReturn(Mono.just(WebPushSender.Outcome.GONE));
        when(sender.send(eq(URI.create(GOOGLE)), any(), any(), any())).thenReturn(Mono.just(WebPushSender.Outcome.DELIVERED));
        when(subscriptions.remove(APPLE)).thenReturn(Mono.empty());

        sut.deliver(FINISHED, MESSAGE)
            .as(StepVerifier::create)
            .verifyComplete();

        verify(subscriptions).remove(APPLE);
        verify(subscriptions, never()).remove(GOOGLE);
    }

    //the next message that finds it tries again
    @Test
    void should_not_fail_the_message_when_the_removal_of_a_dropped_subscription_fails() {
        when(presentMembers.present()).thenReturn(Mono.just(List.of("Ola")));
        when(subscriptions.ofMembers(any())).thenReturn(Mono.just(List.of(subscription(APPLE))));
        when(sender.send(any(), any(), any(), any())).thenReturn(Mono.just(WebPushSender.Outcome.GONE));
        when(subscriptions.remove(APPLE)).thenReturn(Mono.error(new IllegalStateException("database-service answered 500")));

        sut.deliver(FINISHED, MESSAGE)
            .as(StepVerifier::create)
            .verifyComplete();
    }

    @Test
    void should_fail_when_no_browser_at_home_took_the_message() {
        when(presentMembers.present()).thenReturn(Mono.just(List.of("Ola")));
        when(subscriptions.ofMembers(any())).thenReturn(Mono.just(List.of(subscription(APPLE), subscription(GOOGLE))));
        when(sender.send(any(), any(), any(), any())).thenReturn(Mono.just(WebPushSender.Outcome.FAILED));

        sut.deliver(FINISHED, MESSAGE)
            .as(StepVerifier::create)
            .expectErrorMessage("No browser at home took the push message")
            .verify();
    }

    @Test
    void should_be_done_when_one_browser_took_it_and_another_failed() {
        when(presentMembers.present()).thenReturn(Mono.just(List.of("Ola")));
        when(subscriptions.ofMembers(any())).thenReturn(Mono.just(List.of(subscription(APPLE), subscription(GOOGLE))));
        when(sender.send(eq(URI.create(APPLE)), any(), any(), any())).thenReturn(Mono.just(WebPushSender.Outcome.FAILED));
        when(sender.send(eq(URI.create(GOOGLE)), any(), any(), any())).thenReturn(Mono.just(WebPushSender.Outcome.DELIVERED));

        sut.deliver(FINISHED, MESSAGE)
            .as(StepVerifier::create)
            .verifyComplete();
    }

    @Test
    void should_be_done_when_the_members_at_home_have_no_browser_subscribed() {
        when(presentMembers.present()).thenReturn(Mono.just(List.of("Ola")));
        when(subscriptions.ofMembers(any())).thenReturn(Mono.just(List.of()));

        sut.deliver(FINISHED, MESSAGE)
            .as(StepVerifier::create)
            .verifyComplete();

        verifyNoInteractions(sender);
    }

    //nobody has to act on the acknowledgement: it replaces the notification without a sound
    @Test
    void should_replace_the_notification_silently_with_the_acknowledgement() {
        final HouseholdNotification acknowledged = HouseholdNotification.builder()
            .event(HouseholdEvent.APPLIANCE_ACKNOWLEDGED).subject("Dishwasher").deviceId(133L)
            .occurredAt(LocalDateTime.of(2026, 10, 25, 22, 30)).reminder(2).build();
        final HouseholdNotification reminder = HouseholdNotification.builder()
            .event(HouseholdEvent.APPLIANCE_REMINDER).subject("Dishwasher").deviceId(133L)
            .occurredAt(LocalDateTime.of(2026, 10, 25, 15, 15)).reminder(1).acknowledgePath("/room").build();

        assertThat(shown(acknowledged).get("renotify").asBoolean()).isFalse();
        assertThat(shown(acknowledged).get("tag").asString()).isEqualTo("appliance-133");
        assertThat(shown(reminder).get("renotify").asBoolean()).isTrue();
    }

    //database-service reads at most 20 names at once
    @Test
    void should_ask_for_the_subscriptions_of_a_large_household_in_turns() {
        final List<String> members = java.util.stream.IntStream.rangeClosed(1, 21).mapToObj(i -> "Member" + i).toList();
        when(presentMembers.present()).thenReturn(Mono.just(members));
        when(subscriptions.ofMembers(members.subList(0, 20))).thenReturn(Mono.just(List.of(subscription(APPLE))));
        when(subscriptions.ofMembers(members.subList(20, 21))).thenReturn(Mono.just(List.of(subscription(GOOGLE))));
        when(sender.send(any(), any(), any(), any())).thenReturn(Mono.just(WebPushSender.Outcome.DELIVERED));

        sut.deliver(FINISHED, MESSAGE)
            .as(StepVerifier::create)
            .verifyComplete();

        verify(sender, org.mockito.Mockito.times(2)).send(any(), any(), any(), any());
    }

    private JsonNode shown(final HouseholdNotification notification) {
        return jsonMapper.readTree(PushPayload.of(notification, HouseholdMessages.of(notification), jsonMapper)).get("notification");
    }

    private static PushSubscription subscription(final String endpoint) {
        return PushSubscription.builder()
            .endpoint(endpoint)
            .keys(PushSubscriptionKeys.builder().p256dh("BAAA").auth("AAAA").build())
            .build();
    }
}
