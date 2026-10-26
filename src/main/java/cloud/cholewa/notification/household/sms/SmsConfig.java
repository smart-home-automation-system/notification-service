package cloud.cholewa.notification.household.sms;

import cloud.cholewa.notification.household.members.HouseholdRegistryClient;
import cloud.cholewa.notification.household.members.HouseholdServicesConfig;
import cloud.cholewa.notification.household.members.PresentMembersClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.resources.ConnectionProvider;
import tools.jackson.databind.json.JsonMapper;

/**
 * The SMS transport (HAS-275) and the client of SMSAPI - only with {@code household.sms.enabled}: without it
 * there is no SMS transport and the smoke test refuses {@code sms=true}. The token and the sender are checked
 * at the start: without them every SMS would be refused, and only the log would say so. The calls to SMSAPI
 * are kept out of Logbook ({@code logbook.client.predicate.exclude}): the request holds the phone of a member.
 */
@Configuration
@ConditionalOnBooleanProperty("household.sms.enabled")
@EnableConfigurationProperties(SmsProperties.class)
class SmsConfig {

    @Bean(destroyMethod = "dispose")
    ConnectionProvider smsApiConnectionProvider() {
        return HouseholdServicesConfig.connectionProvider("smsapi");
    }

    @Bean
    WebClient smsApiWebClient(
        final WebClient.Builder webClientBuilder,
        final ConnectionProvider smsApiConnectionProvider,
        final SmsProperties properties
    ) {
        return HouseholdServicesConfig.webClient(webClientBuilder.baseUrl(properties.url()), smsApiConnectionProvider,
            properties.connectTimeout(), properties.responseTimeout());
    }

    @Bean
    SmsApiClient smsApiClient(
        @Qualifier("smsApiWebClient") final WebClient smsApiWebClient,
        final SmsProperties properties,
        final JsonMapper jsonMapper
    ) {
        return new SmsApiClient(smsApiWebClient, checked(properties.sender(), "household.sms.sender"),
            checked(properties.token(), "household.sms.token"), jsonMapper);
    }

    @Bean
    SmsRecipients smsRecipients(final PresentMembersClient presentMembers, final HouseholdRegistryClient registry) {
        return new SmsRecipients(presentMembers, registry);
    }

    @Bean
    SmsHouseholdTransport smsHouseholdTransport(final SmsRecipients smsRecipients, final SmsApiClient smsApiClient) {
        return new SmsHouseholdTransport(smsRecipients, smsApiClient);
    }

    @Bean
    SmsSmoke smsSmoke(final HouseholdRegistryClient registry, final SmsApiClient smsApiClient) {
        return new SmsSmoke(registry, smsApiClient);
    }

    static String checked(final String value, final String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(property + " is required while household.sms.enabled is on");
        }
        return value.strip();
    }
}
