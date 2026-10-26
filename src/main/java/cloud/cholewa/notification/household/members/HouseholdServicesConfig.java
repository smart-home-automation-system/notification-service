package cloud.cholewa.notification.household.members;

import cloud.cholewa.notification.config.InternalServiceProperties;
import io.netty.channel.ChannelOption;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;

/**
 * The services the household transports ask who to reach (HAS-276 the push, HAS-275 the SMS): who is at
 * home (presence-service) and the household registry (database-service), over k8s DNS. One WebClient per
 * service, each with its own timeouts and pool, from the injected builder - traced, and logged by the
 * Logbook filter of cholewa-commons.
 */
@Configuration
@EnableConfigurationProperties(InternalServiceProperties.class)
public class HouseholdServicesConfig {

    //Without a limit an idle connection stays in the pool for ever: after a restart of the target, or
    //once conntrack has forgotten it, the next call is written onto a dead socket - and a POST is never
    //repeated (the lesson of the gateway, HAS-212). The same limits as there
    public static final Duration MAX_IDLE_TIME = Duration.ofMinutes(2);
    public static final Duration MAX_LIFE_TIME = Duration.ofMinutes(30);

    @Bean(destroyMethod = "dispose")
    ConnectionProvider presenceConnectionProvider() {
        return connectionProvider("presence-service");
    }

    @Bean(destroyMethod = "dispose")
    ConnectionProvider databaseConnectionProvider() {
        return connectionProvider("database-service");
    }

    @Bean
    WebClient presenceWebClient(
        final WebClient.Builder webClientBuilder,
        final ConnectionProvider presenceConnectionProvider,
        final InternalServiceProperties properties
    ) {
        final InternalServiceProperties.Endpoint presence = properties.presence();
        return webClient(webClientBuilder.baseUrl(presence.url()), presenceConnectionProvider,
            presence.connectTimeout(), presence.responseTimeout());
    }

    @Bean
    WebClient databaseWebClient(
        final WebClient.Builder webClientBuilder,
        final ConnectionProvider databaseConnectionProvider,
        final InternalServiceProperties properties
    ) {
        final InternalServiceProperties.Endpoint database = properties.database();
        return webClient(webClientBuilder.baseUrl(database.url()), databaseConnectionProvider,
            database.connectTimeout(), database.responseTimeout());
    }

    @Bean
    PresentMembersClient presentMembersClient(@Qualifier("presenceWebClient") final WebClient presenceWebClient) {
        return new PresentMembersClient(presenceWebClient);
    }

    @Bean
    HouseholdRegistryClient householdRegistryClient(@Qualifier("databaseWebClient") final WebClient databaseWebClient) {
        return new HouseholdRegistryClient(databaseWebClient);
    }

    public static ConnectionProvider connectionProvider(final String name) {
        return ConnectionProvider.builder(name)
            .maxIdleTime(MAX_IDLE_TIME)
            .maxLifeTime(MAX_LIFE_TIME)
            .build();
    }

    public static WebClient webClient(
        final WebClient.Builder webClientBuilder,
        final ConnectionProvider connectionProvider,
        final Duration connectTimeout,
        final Duration responseTimeout
    ) {
        final HttpClient httpClient = HttpClient.create(connectionProvider)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.toIntExact(connectTimeout.toMillis()))
            .responseTimeout(responseTimeout);
        return webClientBuilder
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .build();
    }
}
