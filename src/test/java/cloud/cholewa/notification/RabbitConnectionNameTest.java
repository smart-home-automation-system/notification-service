package cloud.cholewa.notification;

import cloud.cholewa.commons.notification.NotificationPublisher;
import discord4j.core.DiscordClient;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionNameStrategy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The name the broker shows for the connection of this service comes from cholewa-commons since
 * HAS-206. What is pinned here is what the library cannot know: that in the context of this
 * service its strategy is the one in use, on the connection factory Spring Boot configures, and
 * what the name then is.
 * <p>
 * The test document of application.yaml excludes RabbitAutoConfiguration; these contexts put it
 * back, because the connection factory is the subject. Nothing connects: the listener containers
 * are not started, a connection is opened by the first use only, and the broker address is one
 * nothing listens on.
 */
class RabbitConnectionNameTest {

    private static final String STRATEGY_BEAN = "cholewaPodConnectionNameStrategy";

    @Nested
    @SpringBootTest(properties = {
        "spring.autoconfigure.exclude=",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.rabbitmq.host=localhost",
        "spring.rabbitmq.port=1",
        //set here, above the environment of the machine, so the test reads the same on a
        //workstation, in CI and in a container
        "HOSTNAME=notification-service-5968757896-bghz5"
    })
    @ActiveProfiles("test")
    class InAPod {

        @MockitoBean
        private DiscordClient skippy;

        @Autowired
        private ApplicationContext context;

        @Test
        void should_take_the_connection_name_strategy_from_the_library() {
            assertThat(context.getBeansOfType(ConnectionNameStrategy.class)).containsOnlyKeys(STRATEGY_BEAN);
        }

        @Test
        void should_name_the_auto_configured_connection_after_the_pod() {
            assertThat(connectionNameOf(context)).isEqualTo("notification-service-5968757896-bghz5");
        }

        //this service consumes the notifications; the publisher of the library is opt-in and has
        //to stay off here
        @Test
        void should_not_create_a_notification_publisher() {
            assertThat(context.getBeansOfType(NotificationPublisher.class)).isEmpty();
        }
    }

    @Nested
    @SpringBootTest(properties = {
        "spring.autoconfigure.exclude=",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.rabbitmq.host=localhost",
        "spring.rabbitmq.port=1",
        "HOSTNAME=DESKTOP-4F2K"
    })
    @ActiveProfiles("test")
    class OutsideTheCluster {

        @MockitoBean
        private DiscordClient skippy;

        @Autowired
        private ApplicationContext context;

        @Test
        void should_name_the_auto_configured_connection_local() {
            assertThat(connectionNameOf(context)).isEqualTo("notification-service-local");
        }
    }

    //the strategy the factory holds, asked the way the factory asks it when it opens a connection
    private static String connectionNameOf(final ApplicationContext context) {
        final ConnectionFactory connectionFactory = context.getBean(ConnectionFactory.class);
        assertThat(connectionFactory).isInstanceOf(CachingConnectionFactory.class);

        final ConnectionNameStrategy inUse = (ConnectionNameStrategy)
            ReflectionTestUtils.getField(connectionFactory, "connectionNameStrategy");
        assertThat(inUse).isSameAs(context.getBean(STRATEGY_BEAN));

        return inUse.obtainNewConnectionName(connectionFactory);
    }
}
