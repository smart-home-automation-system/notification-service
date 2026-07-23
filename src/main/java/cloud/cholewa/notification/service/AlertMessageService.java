package cloud.cholewa.notification.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Slf4j
@Service
@RequiredArgsConstructor
public class AlertMessageService {
    
    public Mono<Void> processMessage(String message) {
        log.info("Received message: {}", message);
        return Mono.empty();
    }
}
