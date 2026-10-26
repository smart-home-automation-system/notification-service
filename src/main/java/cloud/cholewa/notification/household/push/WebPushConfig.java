package cloud.cholewa.notification.household.push;

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

import java.security.GeneralSecurityException;
import java.security.interfaces.ECPrivateKey;
import java.time.Clock;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * The Web Push transport (HAS-276) and everything it calls - only with {@code household.push.enabled}:
 * without it there is no push transport at all, and the dispatcher delivers by the others.
 * <p>
 * The VAPID pair and the subject are checked here, at the start: a key that cannot be read, or a public
 * key that is not the pair of the private one, would otherwise fail every message, and only the log
 * would say so. One WebClient per service called, each with its own timeouts and pool, from the injected
 * builder - traced, and logged by the Logbook filter of cholewa-commons; the calls to the push services
 * are kept out of that log by {@code logbook.client.predicate.exclude} (their address is the
 * subscription itself, and the body is ciphertext).
 */
@Configuration
@ConditionalOnBooleanProperty("household.push.enabled")
@EnableConfigurationProperties(WebPushProperties.class)
class WebPushConfig {

    //no quote and no backslash: the subject goes into the JSON of the token as it is
    private static final Pattern SUBJECT = Pattern.compile("^(mailto:[^\\s\"\\\\]+@[^\\s\"\\\\]+|https://[^\\s\"\\\\]+)$");
    private static final Duration PUSH_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration PUSH_RESPONSE_TIMEOUT = Duration.ofSeconds(10);

    @Bean
    VapidSigner vapidSigner(final WebPushProperties properties) {
        //the k= of the header in the one form the browsers subscribed with: base64url, unpadded
        return new VapidSigner(checkedPrivateKey(properties), P256.encode(P256.decode(properties.publicKey())), checkedSubject(properties),
            Clock.systemUTC());
    }

    @Bean
    WebPushEncryptor webPushEncryptor() {
        return new WebPushEncryptor();
    }

    @Bean(destroyMethod = "dispose")
    ConnectionProvider pushConnectionProvider() {
        return HouseholdServicesConfig.connectionProvider("push-services");
    }

    //no base URL: every push service of the allowed hosts is posted to by the address of a subscription
    @Bean
    WebClient pushWebClient(final WebClient.Builder webClientBuilder, final ConnectionProvider pushConnectionProvider) {
        return HouseholdServicesConfig.webClient(webClientBuilder, pushConnectionProvider, PUSH_CONNECT_TIMEOUT, PUSH_RESPONSE_TIMEOUT);
    }

    @Bean
    PushSubscriptionsClient pushSubscriptionsClient(@Qualifier("databaseWebClient") final WebClient databaseWebClient) {
        return new PushSubscriptionsClient(databaseWebClient);
    }

    @Bean
    WebPushSender webPushSender(
        @Qualifier("pushWebClient") final WebClient pushWebClient,
        final WebPushEncryptor encryptor,
        final VapidSigner signer,
        final WebPushProperties properties
    ) {
        return new WebPushSender(pushWebClient, encryptor, signer, properties.ttl());
    }

    @Bean
    WebPushHouseholdTransport webPushHouseholdTransport(
        final PresentMembersClient presentMembersClient,
        final PushSubscriptionsClient pushSubscriptionsClient,
        final WebPushSender webPushSender,
        final WebPushProperties properties,
        final JsonMapper jsonMapper
    ) {
        return new WebPushHouseholdTransport(presentMembersClient, pushSubscriptionsClient, webPushSender,
            properties.allowedHosts(), jsonMapper);
    }

    static ECPrivateKey checkedPrivateKey(final WebPushProperties properties) {
        if (isBlank(properties.privateKey()) || isBlank(properties.publicKey())) {
            throw new IllegalStateException(
                "household.push.private-key and household.push.public-key are required while household.push.enabled is on");
        }
        try {
            final ECPrivateKey privateKey = P256.privateKey(P256.decode(properties.privateKey()));
            final byte[] publicKey = P256.decode(properties.publicKey());
            P256.publicKey(publicKey);
            if (!VapidPair.matches(privateKey, publicKey)) {
                throw new IllegalStateException(
                    "household.push.public-key is not the public key of household.push.private-key");
            }
            return privateKey;
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            //the message of the JDK names no key material
            throw new IllegalStateException("The VAPID keys of household.push cannot be read: " + e.getMessage(), e);
        }
    }

    static String checkedSubject(final WebPushProperties properties) {
        if (isBlank(properties.subject()) || !SUBJECT.matcher(properties.subject().strip()).matches()) {
            throw new IllegalStateException(
                "household.push.subject must be a mailto: address or an https: URL while household.push.enabled is on");
        }
        return properties.subject().strip();
    }

    private static boolean isBlank(final String value) {
        return value == null || value.isBlank();
    }
}
