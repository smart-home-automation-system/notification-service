package cloud.cholewa.notification.household.sms;

import cloud.cholewa.notification.household.HouseholdTransport;
import discord4j.core.DiscordClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

//the whole context with the SMS switched on (HAS-275): the dispatcher delivers by Discord and by SMS
@SpringBootTest(properties = {
    "household.sms.enabled=true",
    "household.sms.sender=smart-home",
    "household.sms.token=test-token-not-real"
})
@ActiveProfiles("test")
class SmsEnabledContextTest {

    @MockitoBean
    private DiscordClient skippy;

    @Autowired
    private List<HouseholdTransport> transports;

    @Autowired
    private SmsSmoke smsSmoke;

    @Test
    void should_deliver_by_discord_and_by_sms() {
        assertThat(transports).extracting(HouseholdTransport::name).containsExactlyInAnyOrder("discord", "sms");
        assertThat(smsSmoke).isNotNull();
    }
}
