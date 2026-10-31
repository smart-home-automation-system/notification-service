package cloud.cholewa.notification.household.sms;

import cloud.cholewa.notification.household.HouseholdTransport;
import discord4j.core.DiscordClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
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

    @Autowired
    private ObjectProvider<MarkedNotificationSms> markedNotificationSms;

    @Test
    void should_deliver_by_discord_and_by_sms() {
        assertThat(transports).extracting(HouseholdTransport::name).containsExactlyInAnyOrder("discord", "sms");
        assertThat(smsSmoke).isNotNull();
    }

    //HAS-335: what NotificationMessageService takes through its ObjectProvider - absent, a marked message
    //would go to Discord alone with nothing but a WARN
    @Test
    void should_text_the_messages_marked_for_an_sms() {
        assertThat(markedNotificationSms.getIfAvailable()).isNotNull();
    }
}
