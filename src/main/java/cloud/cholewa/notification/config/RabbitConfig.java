package cloud.cholewa.notification.config;

import org.springframework.amqp.rabbit.connection.ConnectionNameStrategy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    //the name the broker shows for the connection: the pod, which already says which service it
    //is and tells the old pod from the new one during a rollout. Kubernetes sets HOSTNAME;
    //outside the cluster there is none
    @Bean
    ConnectionNameStrategy connectionNameStrategy(
        @Value("${HOSTNAME:notification-service-local}") final String hostname
    ) {
        return connectionFactory -> hostname;
    }
}
