package cloud.cholewa.notification.rabbit;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cloud.cholewa.notification.model.NotificationLevel;
import cloud.cholewa.notification.service.NotificationMessageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Answers.RETURNS_SMART_NULLS;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RabbitNotificationConsumerTest {

    private static final String ALERT = "alert";
    private static final String MESSAGE = "dummy message";

    @Mock(answer = RETURNS_SMART_NULLS)
    private NotificationMessageService notificationMessageService;

    @InjectMocks
    private RabbitNotificationConsumer sut;

    private final Logger logger = (Logger) LoggerFactory.getLogger(RabbitNotificationConsumer.class);
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

    //the default level of a method is right only as long as the method listens on its own queue
    @ParameterizedTest
    @CsvSource({
        "consumeAlertMessage, ${rabbit.alert.queue}",
        "consumeInfoMessage, ${rabbit.info.queue}"
    })
    void should_listen_on_its_own_queue(final String method, final String queue) throws NoSuchMethodException {
        final RabbitListener listener = RabbitNotificationConsumer.class
            .getDeclaredMethod(method, String.class, String.class)
            .getAnnotation(RabbitListener.class);

        assertThat(listener.queues()).containsExactly(queue);
    }

    @ParameterizedTest(name = "{0} message with the level header [{1}] is shown as {2}")
    @CsvSource(nullValues = "-", value = {
        //without a level the queue decides
        "alert, -, ERROR",
        "info, -, INFO",
        //the level named by the publisher wins on both queues
        "alert, warn, WARN",
        "alert, info, INFO",
        "info, warn, WARN",
        "info, error, ERROR",
        //an unknown level is no level
        "alert, fatal, ERROR",
        "info, fatal, INFO"
    })
    void should_take_the_level_from_the_header_and_fall_back_to_the_queue(
        final String category,
        final String level,
        final NotificationLevel expected
    ) {
        when(notificationMessageService.processMessage(expected, MESSAGE)).thenReturn(Mono.empty());

        consume(category, level)
            .as(StepVerifier::create)
            .verifyComplete();

        verify(notificationMessageService).processMessage(expected, MESSAGE);
        verifyNoMoreInteractions(notificationMessageService);
    }

    @ParameterizedTest
    @CsvSource({
        "alert, ERROR, Received alert message: dummy message",
        "info, INFO, Received info message: dummy message"
    })
    void should_log_the_received_message(
        final String category,
        final NotificationLevel defaultLevel,
        final String expectedLog
    ) {
        when(notificationMessageService.processMessage(defaultLevel, MESSAGE)).thenReturn(Mono.empty());

        consume(category, null)
            .as(StepVerifier::create)
            .verifyComplete();

        assertThat(logs.list)
            .extracting(ILoggingEvent::getLevel, ILoggingEvent::getFormattedMessage)
            .containsExactly(tuple(Level.INFO, expectedLog));
    }

    //an error signal would make the container hand the message back and receive it again at once,
    //and the level matters: ERROR is what the alerts on the logs see
    @ParameterizedTest
    @CsvSource({
        "alert, ERROR, Alert message not delivered: dummy message - Error",
        "info, INFO, Info message not delivered: dummy message - Error"
    })
    void should_complete_and_log_the_message_at_error_when_delivery_fails(
        final String category,
        final NotificationLevel defaultLevel,
        final String expectedLog
    ) {
        when(notificationMessageService.processMessage(defaultLevel, MESSAGE))
            .thenReturn(Mono.error(new RuntimeException("Error")));

        consume(category, null)
            .as(StepVerifier::create)
            .verifyComplete();

        assertThat(logs.list)
            .filteredOn(event -> event.getLevel() == Level.ERROR)
            .extracting(ILoggingEvent::getFormattedMessage)
            .containsExactly(expectedLog);
    }

    //thrown before there is a Mono to signal it: without the defer it would leave the listener
    //method and start the same redelivery loop
    @ParameterizedTest
    @CsvSource({
        "alert, ERROR, Alert message not delivered: dummy message - Error",
        "info, INFO, Info message not delivered: dummy message - Error"
    })
    void should_complete_and_log_the_message_at_error_when_delivery_throws(
        final String category,
        final NotificationLevel defaultLevel,
        final String expectedLog
    ) {
        when(notificationMessageService.processMessage(defaultLevel, MESSAGE))
            .thenThrow(new RuntimeException("Error"));

        consume(category, null)
            .as(StepVerifier::create)
            .verifyComplete();

        assertThat(logs.list)
            .filteredOn(event -> event.getLevel() == Level.ERROR)
            .extracting(ILoggingEvent::getFormattedMessage)
            .containsExactly(expectedLog);
    }

    private Mono<Void> consume(final String category, final String level) {
        return ALERT.equals(category)
            ? sut.consumeAlertMessage(MESSAGE, level)
            : sut.consumeInfoMessage(MESSAGE, level);
    }
}
