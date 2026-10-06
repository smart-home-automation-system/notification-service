package cloud.cholewa.notification.rabbit;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cloud.cholewa.notification.model.NotificationLevel;
import cloud.cholewa.notification.service.NotificationMessageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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

    private static final String ALERT_QUEUE = "notification.test.alert";
    private static final String INFO_QUEUE = "notification.test.info";

    @Mock(answer = RETURNS_SMART_NULLS)
    private NotificationMessageService notificationMessageService;

    private RabbitNotificationConsumer sut;

    private final Logger logger = (Logger) LoggerFactory.getLogger(RabbitNotificationConsumer.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void setUp() {
        sut = new RabbitNotificationConsumer(notificationMessageService, INFO_QUEUE);
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
    }

    @Test
    void should_listen_on_the_alert_and_the_info_queue() throws NoSuchMethodException {
        final RabbitListener listener = RabbitNotificationConsumer.class
            .getDeclaredMethod("consumeMessage", String.class, String.class, String.class)
            .getAnnotation(RabbitListener.class);

        assertThat(listener.queues()).containsExactlyInAnyOrder("${rabbit.alert.queue}", "${rabbit.info.queue}");
    }

    @ParameterizedTest(name = "a message from {0} with the level header [{1}] is shown as {2}")
    @CsvSource(nullValues = "-", value = {
        //without a level the queue decides
        ALERT_QUEUE + ", -, ERROR",
        INFO_QUEUE + ", -, INFO",
        //the level named by the publisher wins on both queues
        ALERT_QUEUE + ", warn, WARN",
        ALERT_QUEUE + ", info, INFO",
        INFO_QUEUE + ", warn, WARN",
        INFO_QUEUE + ", error, ERROR",
        //an unknown level is no level
        ALERT_QUEUE + ", fatal, ERROR",
        INFO_QUEUE + ", fatal, INFO",
        //a queue that is neither of the two, or none at all, must not cost the notification
        "notification.test.other, -, ERROR",
        "-, -, ERROR",
        "-, info, INFO"
    })
    void should_take_the_level_from_the_header_and_fall_back_to_the_queue(
        final String queue,
        final String level,
        final NotificationLevel expected
    ) {
        when(notificationMessageService.processMessage(expected, "dummy message")).thenReturn(Mono.empty());

        sut.consumeMessage("dummy message", queue, level)
            .as(StepVerifier::create)
            .verifyComplete();

        verify(notificationMessageService).processMessage(expected, "dummy message");
        verifyNoMoreInteractions(notificationMessageService);
    }

    @Test
    void should_log_the_received_message_with_its_queue() {
        when(notificationMessageService.processMessage(NotificationLevel.ERROR, "dummy message"))
            .thenReturn(Mono.empty());

        sut.consumeMessage("dummy message", ALERT_QUEUE, null)
            .as(StepVerifier::create)
            .verifyComplete();

        assertThat(logs.list)
            .extracting(ILoggingEvent::getLevel, ILoggingEvent::getFormattedMessage)
            .containsExactly(
                tuple(
                    Level.INFO,
                    "Received message from notification.test.alert: dummy message"
                )
            );
    }

    //an error signal would make the container hand the message back and receive it again at once,
    //and the level matters: ERROR is what the alerts on the logs see
    @ParameterizedTest
    @CsvSource({ALERT_QUEUE, INFO_QUEUE})
    void should_complete_and_log_the_message_at_error_when_delivery_fails(final String queue) {
        when(notificationMessageService.processMessage(
            INFO_QUEUE.equals(queue) ? NotificationLevel.INFO : NotificationLevel.ERROR,
            "dummy message"
        )).thenReturn(Mono.error(new RuntimeException("Error")));

        sut.consumeMessage("dummy message", queue, null)
            .as(StepVerifier::create)
            .verifyComplete();

        assertThat(logs.list)
            .filteredOn(event -> event.getLevel() == Level.ERROR)
            .extracting(ILoggingEvent::getFormattedMessage)
            .containsExactly("Message from " + queue + " not delivered: dummy message - Error");
    }
}
