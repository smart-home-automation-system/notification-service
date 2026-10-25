package cloud.cholewa.notification.household.push;

import cloud.cholewa.notification.household.HouseholdDispatcher;
import cloud.cholewa.notification.household.HouseholdTransport;
import discord4j.core.DiscordClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.util.List;

import static cloud.cholewa.notification.household.push.WebPushConfigTest.privateKeyOf;
import static cloud.cholewa.notification.household.push.WebPushConfigTest.publicKeyOf;
import static org.assertj.core.api.Assertions.assertThat;

//the whole context with the transport switched on: the dispatcher delivers by Discord and by push
@SpringBootTest
@ActiveProfiles("test")
class WebPushEnabledContextTest {

    @MockitoBean
    private DiscordClient skippy;

    private static final KeyPair PAIR = pair();

    @DynamicPropertySource
    static void push(final DynamicPropertyRegistry registry) {
        registry.add("household.push.enabled", () -> "true");
        registry.add("household.push.subject", () -> "mailto:owner@example.com");
        registry.add("household.push.private-key", () -> privateKeyOf(PAIR));
        registry.add("household.push.public-key", () -> publicKeyOf(PAIR));
    }

    @Autowired
    private List<HouseholdTransport> transports;

    @Autowired
    private HouseholdDispatcher dispatcher;

    @Test
    void should_deliver_by_discord_and_by_push() {
        assertThat(transports).extracting(HouseholdTransport::name).containsExactlyInAnyOrder("discord", "push");
        assertThat(dispatcher).isNotNull();
    }

    private static KeyPair pair() {
        try {
            return VapidSignerTest.pair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
