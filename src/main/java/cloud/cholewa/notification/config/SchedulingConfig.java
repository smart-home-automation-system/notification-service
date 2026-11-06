package cloud.cholewa.notification.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

//The first scheduled task of the service (HAS-410, the SMS of the household requests). Off under the test
//profile, the rule of water-service (HAS-307): a context test would otherwise run what the application
//schedules
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Profile("!test")
public class SchedulingConfig {
}
