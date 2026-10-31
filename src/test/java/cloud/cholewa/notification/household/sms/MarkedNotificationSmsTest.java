package cloud.cholewa.notification.household.sms;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cloud.cholewa.notification.household.members.HouseholdRegistryClient;
import cloud.cholewa.notification.household.members.HouseholdRegistryClient.Member;
import cloud.cholewa.notification.model.NotificationLevel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

//HAS-335: a marked alert or info by SMS to the administrators of the household - never failing the delivery
class MarkedNotificationSmsTest {

    private static final String MESSAGE = "The grid is lost, battery at 64 %";
    private static final Member OWNER = new Member("Krzysztof", "+48500000001", true, "admin");
    private static final Member RESIDENT = new Member("Liwia", "+48500000002", true, "resident");
    private static final Member ADMIN_OFF = new Member("Jan", "+48500000003", false, "admin");
    private static final Member ADMIN_NO_PHONE = new Member("Katarzyna", " ", true, "admin");

    private final HouseholdRegistryClient registry = mock(HouseholdRegistryClient.class);
    private final SmsApiClient client = mock(SmsApiClient.class);
    private final MarkedNotificationSms sut = new MarkedNotificationSms(registry, client);

    private final Logger logger = (Logger) LoggerFactory.getLogger(MarkedNotificationSms.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

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
    void should_text_the_switched_on_administrators_with_a_phone_the_level_and_the_message() {
        when(registry.members()).thenReturn(Mono.just(List.of(OWNER, RESIDENT, ADMIN_OFF, ADMIN_NO_PHONE)));
        when(client.send(anyString(), anyString(), anyString())).thenReturn(Mono.just(true));

        sut.send(NotificationLevel.ERROR, MESSAGE).as(StepVerifier::create).verifyComplete();

        verify(client).send("Krzysztof", "+48500000001", "ERROR: " + MESSAGE);
        assertThat(logs.list)
            .extracting(ILoggingEvent::getLevel, ILoggingEvent::getFormattedMessage)
            .contains(tuple(Level.INFO, "Marked error message texted to 1 of 1 administrator(s): " + MESSAGE));
    }

    //the DoD of HAS-335: Discord has it, one WARN says nobody was texted
    @Test
    void should_complete_with_one_warn_when_no_administrator_has_a_phone() {
        when(registry.members()).thenReturn(Mono.just(List.of(RESIDENT, ADMIN_NO_PHONE)));

        sut.send(NotificationLevel.ERROR, MESSAGE).as(StepVerifier::create).verifyComplete();

        verifyNoInteractions(client);
        assertThat(logs.list)
            .filteredOn(event -> event.getLevel() == Level.WARN)
            .extracting(ILoggingEvent::getFormattedMessage)
            .containsExactly(
                "No administrator of the household has a phone: the marked message went to Discord only: " + MESSAGE);
    }

    @Test
    void should_complete_with_an_error_line_when_the_registry_cannot_be_read() {
        when(registry.members()).thenReturn(Mono.error(new IllegalStateException("database-service answered 503")));

        sut.send(NotificationLevel.INFO, MESSAGE).as(StepVerifier::create).verifyComplete();

        verifyNoInteractions(client);
        assertThat(logs.list)
            .extracting(ILoggingEvent::getLevel, ILoggingEvent::getFormattedMessage)
            .containsExactly(tuple(Level.ERROR,
                "Marked message not texted - IllegalStateException: database-service answered 503 - " + MESSAGE));
    }

    //SmsApiClient logs the refusal with its code; here it is counted
    @Test
    void should_complete_when_smsapi_refuses() {
        when(registry.members()).thenReturn(Mono.just(List.of(OWNER)));
        when(client.send(anyString(), anyString(), anyString())).thenReturn(Mono.just(false));

        sut.send(NotificationLevel.WARN, MESSAGE).as(StepVerifier::create).verifyComplete();

        assertThat(logs.list)
            .extracting(ILoggingEvent::getFormattedMessage)
            .contains("Marked warn message texted to 0 of 1 administrator(s): " + MESSAGE);
    }

    @Test
    void should_read_the_administrators_once_for_several_messages() {
        final AtomicInteger reads = new AtomicInteger();
        when(registry.members()).thenReturn(Mono.fromSupplier(() -> {
            reads.incrementAndGet();
            return List.of(OWNER);
        }));
        when(client.send(anyString(), anyString(), anyString())).thenReturn(Mono.just(true));

        sut.send(NotificationLevel.ERROR, MESSAGE).as(StepVerifier::create).verifyComplete();
        sut.send(NotificationLevel.INFO, MESSAGE).as(StepVerifier::create).verifyComplete();

        assertThat(reads).hasValue(1);
        assertThat(logs.list)
            .extracting(ILoggingEvent::getFormattedMessage)
            .containsOnlyOnce("Administrators of the household with a phone read from the registry: 1");
    }

    //a failed read is not kept: the next marked message asks again
    @Test
    void should_ask_the_registry_again_after_a_failed_read() {
        final AtomicInteger reads = new AtomicInteger();
        when(registry.members()).thenReturn(Mono.defer(() -> reads.incrementAndGet() == 1
            ? Mono.error(new IllegalStateException("down"))
            : Mono.just(List.of(OWNER))));
        when(client.send(anyString(), anyString(), anyString())).thenReturn(Mono.just(true));

        sut.send(NotificationLevel.ERROR, MESSAGE).as(StepVerifier::create).verifyComplete();
        sut.send(NotificationLevel.ERROR, MESSAGE).as(StepVerifier::create).verifyComplete();

        assertThat(reads).hasValue(2);
        verify(client).send("Krzysztof", "+48500000001", "ERROR: " + MESSAGE);
    }

    @Test
    void should_cut_a_long_message_to_one_sms_with_the_level_in_front() {
        final String text = SmsText.of(NotificationLevel.WARN, "  " + "x".repeat(300) + "  ");

        assertThat(text).hasSize(SmsText.MAX_LENGTH).startsWith("WARN: x").endsWith("...");
        assertThat(SmsText.of(NotificationLevel.INFO, "The grid is back")).isEqualTo("INFO: The grid is back");
    }
}
