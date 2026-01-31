package cloud.cholewa.notification.config;

import org.springframework.amqp.rabbit.connection.ConnectionNameStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {
    
    @Bean
    ConnectionNameStrategy connectionNameStrategy() {
        return connection -> "consumer-notification-service-" + connection.getHost();
    }
}
