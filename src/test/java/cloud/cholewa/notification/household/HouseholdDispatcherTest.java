package cloud.cholewa.notification.household;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cloud.cholewa.home.model.HouseholdEvent;
import cloud.cholewa.home.model.HouseholdNotification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

//every transport tried, all at once; a failed one never fails the others nor the message
class HouseholdDispatcherTest {

    private static final HouseholdNotification NOTIFICATION = HouseholdNotification.builder()
        .event(HouseholdEvent.APPLIANCE_FINISHED).subject("Zmywarka").deviceId(64L)
        .occurredAt(LocalDateTime.of(2026, 10, 24, 19, 5)).reminder(0).build();

    private final Logger logger = (Logger) LoggerFactory.getLogger(HouseholdDispatcher.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final List<String> delivered = new ArrayList<>();

    @BeforeEach
    void setUp() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
    }

    @Test
    void should_deliver_the_worded_message_by_every_transport() {
        final HouseholdDispatcher sut = new HouseholdDispatcher(List.of(working("discord"), working("sms")));

        sut.dispatch(NOTIFICATION).as(StepVerifier::create).verifyComplete();

        assertThat(delivered).containsExactly(
            "discord: Koniec pracy: Zmywarka - wymaga opróżnienia.",
            "sms: Koniec pracy: Zmywarka - wymaga opróżnienia.");
    }

    //a signalled error and one thrown while building the delivery alike
    @Test
    void should_try_the_next_transport_when_one_fails_and_name_the_one_that_did() {
        final HouseholdDispatcher sut = new HouseholdDispatcher(List.of(
            failing("discord", true), failing("push", false), working("sms")));

        sut.dispatch(NOTIFICATION).as(StepVerifier::create).verifyComplete();

        assertThat(delivered).containsExactly("sms: Koniec pracy: Zmywarka - wymaga opróżnienia.");
        assertThat(logs.list)
            .filteredOn(event -> event.getLevel() == Level.ERROR)
            .extracting(ILoggingEvent::getFormattedMessage)
            .containsExactly(
                "Household notification [appliance finished] of [Zmywarka] not delivered by [discord]: IllegalStateException - down",
                "Household notification [appliance finished] of [Zmywarka] not delivered by [push]: IllegalStateException - down");
    }

    //Discord retrying for a minute must not hold back the SMS: all at once, completed when all are
    @Test
    void should_not_let_a_slow_transport_hold_back_the_others() {
        final HouseholdTransport slow = new HouseholdTransport() {
            @Override
            public String name() {
                return "discord";
            }

            @Override
            public Mono<Void> deliver(final HouseholdNotification notification, final HouseholdMessage message) {
                return Mono.delay(Duration.ofMinutes(1)).then(Mono.fromRunnable(() -> delivered.add("discord")));
            }
        };

        StepVerifier.withVirtualTime(() -> new HouseholdDispatcher(List.of(slow, working("sms"))).dispatch(NOTIFICATION))
            .expectSubscription()
            .then(() -> assertThat(delivered).containsExactly("sms: Koniec pracy: Zmywarka - wymaga opróżnienia."))
            .expectNoEvent(Duration.ofSeconds(59))
            .thenAwait(Duration.ofSeconds(1))
            .verifyComplete();

        assertThat(delivered).containsExactly("sms: Koniec pracy: Zmywarka - wymaga opróżnienia.", "discord");
    }

    @Test
    void should_complete_with_no_transport_at_all() {
        new HouseholdDispatcher(List.of()).dispatch(NOTIFICATION).as(StepVerifier::create).verifyComplete();
    }

    private HouseholdTransport working(final String name) {
        return new HouseholdTransport() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Mono<Void> deliver(final HouseholdNotification notification, final HouseholdMessage message) {
                return Mono.fromRunnable(() -> delivered.add(name + ": " + message.text()));
            }
        };
    }

    private static HouseholdTransport failing(final String name, final boolean signalled) {
        return new HouseholdTransport() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Mono<Void> deliver(final HouseholdNotification notification, final HouseholdMessage message) {
                if (!signalled) {
                    throw new IllegalStateException("down");
                }
                return Mono.error(new IllegalStateException("down"));
            }
        };
    }
}
