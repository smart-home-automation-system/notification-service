package cloud.cholewa.notification.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class AlertMessageServiceTest {

    @InjectMocks
    private AlertMessageService sut;

    @Test
    void shouldCreateAlertMessage() {
        sut.processMessage("message")
            .as(StepVerifier::create)
            .verifyComplete();
    }
}