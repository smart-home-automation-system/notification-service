package cloud.cholewa.notification.rabbit;

import cloud.cholewa.notification.model.NotificationLevel;
import cloud.cholewa.notification.service.NotificationMessageService;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageBuilderSupport;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.listener.MethodRabbitListenerEndpoint;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.messaging.handler.annotation.support.DefaultMessageHandlerMethodFactory;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

import static org.mockito.Answers.RETURNS_SMART_NULLS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * The listener method behind the adapter Spring AMQP puts in front of it, fed with messages as the
 * container hands them over. The unit test calls the method with the queue as a plain argument;
 * only this one shows that the argument really is the queue the message was consumed from - a
 * wrong header name there would turn every info into an ERROR without failing anything else.
 */
@ExtendWith(MockitoExtension.class)
class RabbitNotificationConsumerWiringTest {

    private static final String ALERT_QUEUE = "notification.test.alert";
    private static final String INFO_QUEUE = "notification.test.info";

    @Mock(answer = RETURNS_SMART_NULLS)
    private NotificationMessageService notificationMessageService;

    @Mock
    private Channel channel;

    private ChannelAwareMessageListener listener;

    @BeforeEach
    void setUp() throws NoSuchMethodException {
        final DefaultMessageHandlerMethodFactory handlerMethodFactory = new DefaultMessageHandlerMethodFactory();
        handlerMethodFactory.afterPropertiesSet();

        final MethodRabbitListenerEndpoint endpoint = new MethodRabbitListenerEndpoint();
        endpoint.setId("notification");
        endpoint.setQueueNames(ALERT_QUEUE, INFO_QUEUE);
        endpoint.setBean(new RabbitNotificationConsumer(notificationMessageService, INFO_QUEUE));
        endpoint.setMethod(RabbitNotificationConsumer.class
            .getDeclaredMethod("consumeMessage", String.class, String.class, String.class));
        endpoint.setMessageHandlerMethodFactory(handlerMethodFactory);

        //never started, so it needs no broker: it only receives the listener the endpoint builds
        final SimpleMessageListenerContainer container = new SimpleMessageListenerContainer();
        endpoint.setupListenerContainer(container);
        listener = (ChannelAwareMessageListener) container.getMessageListener();

        when(notificationMessageService.processMessage(any(), any())).thenReturn(Mono.empty());
    }

    @Test
    void should_show_a_message_from_the_alert_queue_as_an_error() throws Exception {
        listener.onMessage(message(ALERT_QUEUE, null), channel);

        verify(notificationMessageService).processMessage(NotificationLevel.ERROR, "dummy message");
        verifyNoMoreInteractions(notificationMessageService);
    }

    @Test
    void should_show_a_message_from_the_info_queue_as_an_info() throws Exception {
        listener.onMessage(message(INFO_QUEUE, null), channel);

        verify(notificationMessageService).processMessage(NotificationLevel.INFO, "dummy message");
        verifyNoMoreInteractions(notificationMessageService);
    }

    @Test
    void should_let_the_level_header_win_over_the_queue() throws Exception {
        listener.onMessage(message(INFO_QUEUE, "warn"), channel);
        listener.onMessage(message(ALERT_QUEUE, "info"), channel);

        verify(notificationMessageService).processMessage(NotificationLevel.WARN, "dummy message");
        verify(notificationMessageService).processMessage(NotificationLevel.INFO, "dummy message");
        verifyNoMoreInteractions(notificationMessageService);
    }

    //plain text with the level as a custom header, which is what heating-service publishes; the
    //queue is not a header on the wire, the consumer notes it on the properties of the delivery
    private static Message message(final String consumerQueue, final String level) {
        final MessageBuilderSupport<Message> builder = MessageBuilder
            .withBody("dummy message".getBytes(StandardCharsets.UTF_8))
            .setContentType(MessageProperties.CONTENT_TYPE_TEXT_PLAIN)
            .setDeliveryTag(1L);
        if (level != null) {
            builder.setHeader(RabbitNotificationConsumer.LEVEL_HEADER, level);
        }

        final Message message = builder.build();
        message.getMessageProperties().setConsumerQueue(consumerQueue);
        return message;
    }
}
