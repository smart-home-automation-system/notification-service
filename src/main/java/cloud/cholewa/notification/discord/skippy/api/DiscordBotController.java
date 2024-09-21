package cloud.cholewa.notification.discord.skippy.api;

import cloud.cholewa.notification.discord.skippy.service.DiscordBotService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequiredArgsConstructor
@RequestMapping("skippy")
public class DiscordBotController {

    private final DiscordBotService service;

    @GetMapping
    Mono<ResponseEntity<Void>> sendMessage(@RequestParam String message) {
        return service.sendMessage(message);
    }
}
