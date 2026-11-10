package cloud.cholewa.notification.household.requests;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * The properties of the household requests, bound whether the passes are scheduled or not - the manual triggers
 * read them too (HAS-416) - and the clock of the house the evening pass reads "today" and "some days ago" by.
 */
@Configuration
@EnableConfigurationProperties(RequestsProperties.class)
class RequestsConfig {

    @Bean
    Clock requestsClock(final RequestsProperties properties) {
        return Clock.system(properties.zone());
    }
}
