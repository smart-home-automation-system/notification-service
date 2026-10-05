package cloud.cholewa.notification.rabbit;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cloud.cholewa.notification.model.NotificationLevel;
import cloud.cholewa.notification.service.NotificationMessageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Answers.RETURNS_SMART_NULLS;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RabbitInfoMessageConsumerTest {

    @Mock(answer = RETURNS_SMART_NULLS)
    private NotificationMessageService notificationMessageService;

    @InjectMocks
    private RabbitInfoMessageConsumer sut;

    @Test
    void should_call_notification_message_service_when_message_received() {
        when(notificationMessageService.processMessage(NotificationLevel.INFO, "dummy message")).thenReturn(Mono.empty());

        sut.consumeInfoMessage("dummy message", null)
            .as(StepVerifier::create)
            .verifyComplete();

        verify(notificationMessageService).processMessage(NotificationLevel.INFO, "dummy message");
        verifyNoMoreInteractions(notificationMessageService);
    }

    //an error signal would make the container hand the message back and receive it again at once
    @Test
    void should_complete_and_log_the_message_when_delivery_fails() {
        final Logger logger = (Logger) LoggerFactory.getLogger(RabbitInfoMessageConsumer.class);
        final ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
        listAppender.start();
        logger.addAppender(listAppender);

        when(notificationMessageService.processMessage(NotificationLevel.INFO, "dummy message"))
            .thenReturn(Mono.error(new RuntimeException("Error")));

        sut.consumeInfoMessage("dummy message", null)
            .as(StepVerifier::create)
            .verifyComplete();

        assertThat(listAppender.list)
            .filteredOn(event -> event.getLevel() == Level.ERROR)
            .extracting(ILoggingEvent::getFormattedMessage)
            .containsExactly("Info message not delivered: dummy message - Error");

        logger.detachAppender(listAppender);
    }
}
