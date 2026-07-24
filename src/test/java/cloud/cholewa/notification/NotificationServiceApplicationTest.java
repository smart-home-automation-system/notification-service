package cloud.cholewa.notification;

import discord4j.core.DiscordClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
class NotificationServiceApplicationTest {

    @MockitoBean
    private DiscordClient skippy;

    @Test
    void contextLoads() {
    }
}
