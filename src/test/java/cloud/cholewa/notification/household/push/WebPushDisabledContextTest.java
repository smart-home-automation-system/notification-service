package cloud.cholewa.notification.household.push;

import cloud.cholewa.notification.household.HouseholdTransport;
import discord4j.core.DiscordClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

//the test profile switches it off: no push transport and nothing it would call
@SpringBootTest
@ActiveProfiles("test")
class WebPushDisabledContextTest {

    @MockitoBean
    private DiscordClient skippy;

    @Autowired
    private List<HouseholdTransport> transports;

    @Autowired
    private ApplicationContext context;

    @Test
    void should_deliver_by_discord_alone() {
        assertThat(transports).extracting(HouseholdTransport::name).containsExactly("discord");
        assertThat(context.containsBean("pushWebClient")).isFalse();
    }
}
