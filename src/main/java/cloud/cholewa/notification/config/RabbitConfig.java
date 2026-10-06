package cloud.cholewa.notification.config;

import org.springframework.amqp.rabbit.connection.ConnectionNameStrategy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    //the name the broker shows for the connection: the pod, which already says which service it
    //is and tells the old pod from the new one during a rollout
    @Bean
    ConnectionNameStrategy connectionNameStrategy(
        @Value("${HOSTNAME:}") final String hostname,
        @Value("${spring.application.name}") final String service
    ) {
        final String name = connectionName(hostname, service);
        return connectionFactory -> name;
    }

    //Kubernetes names a pod after its Deployment and puts that name into HOSTNAME. Anything else
    //found there - nothing, an empty value, a workstation, a container id - is not a pod of this
    //service and would not say who is connected
    static String connectionName(final String hostname, final String service) {
        return hostname.startsWith(service + "-") ? hostname : service + "-local";
    }
}
