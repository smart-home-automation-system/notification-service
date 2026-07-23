package cloud.cholewa.notification.rabbit;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cloud.cholewa.notification.service.AlertMessageService;
import org.junit.jupiter.api.DisplayName;
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
class RabbitAlertMessageConsumerTest {

    @Mock(answer = RETURNS_SMART_NULLS)
    private AlertMessageService alertMessageService;

    @InjectMocks
    private RabbitAlertMessageConsumer sut;

    @Test
    @DisplayName("should call alertMessageService when message received")
    void should_call_alert_message_service_when_message_received() {
        when(alertMessageService.processMessage("dummy message")).thenReturn(Mono.empty());

        sut.consumeAlertMessage("dummy message")
            .as(StepVerifier::create)
            .verifyComplete();

        verify(alertMessageService).processMessage("dummy message");
        verifyNoMoreInteractions(alertMessageService);
    }

    @Test
    @DisplayName("should log info message when message received")
    void should_log_info_message_when_message_received() {
        final Logger logger = (Logger) LoggerFactory.getLogger(RabbitAlertMessageConsumer.class);
        ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
        listAppender.start();
        logger.addAppender(listAppender);

        when(alertMessageService.processMessage("dummy message")).thenReturn(Mono.empty());

        sut.consumeAlertMessage("dummy message")
            .as(StepVerifier::create)
            .verifyComplete();

        assertThat(listAppender.list)
            .extracting(ILoggingEvent::getFormattedMessage)
            .containsExactly("Received alert message: dummy message");

        assertThat(listAppender.list)
            .extracting(ILoggingEvent::getLevel)
            .contains(Level.INFO);

        logger.detachAppender(listAppender);
    }

    @Test
    @DisplayName("should log error when alertMessageService fails")
    void should_log_error_when_alert_message_service_fails() {
        final Logger logger = (Logger) LoggerFactory.getLogger(RabbitAlertMessageConsumer.class);
        ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
        listAppender.start();
        logger.addAppender(listAppender);
        
        when(alertMessageService.processMessage("dummy message"))
            .thenReturn(Mono.error(new RuntimeException("Error")));
        
        sut.consumeAlertMessage("dummy message")
            .as(StepVerifier::create)
            .verifyComplete();

        assertThat(listAppender.list)
            .extracting(ILoggingEvent::getFormattedMessage)
            .element(1)
            .isEqualTo("Error while consuming alert message: Error");
        
        assertThat(listAppender.list)
            .extracting(ILoggingEvent::getLevel)
            .element(1)
            .isEqualTo(Level.ERROR);
        
        logger.detachAppender(listAppender);
    }

    @Test
    @DisplayName("should return empty mono when error occurs")
    void should_return_empty_mono_when_error_occurs() {
        when(alertMessageService.processMessage("dummy message"))
            .thenReturn(Mono.error(new RuntimeException("Error")));

        sut.consumeAlertMessage("dummy message")
            .as(StepVerifier::create)
            .expectNextCount(0)
            .verifyComplete();
        
        verify(alertMessageService).processMessage("dummy message");
        verifyNoMoreInteractions(alertMessageService);
    }
}