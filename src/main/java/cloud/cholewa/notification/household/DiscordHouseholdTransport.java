package cloud.cholewa.notification.household;

import cloud.cholewa.home.model.HouseholdNotification;
import cloud.cholewa.notification.discord.skippy.service.DiscordBotService;
import cloud.cholewa.notification.household.sms.SmsRecipients;
import cloud.cholewa.notification.service.DiscordDeliveryRetry;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.stream.Collectors;

//the household channel on Discord - always, whoever is at home (the owner's decision of 2026-10-11).
//With the SMS switched on (HAS-275) its footer names the members the SMS of the message goes to - the
//same cached read as the SMS transport's, asked at the same moment; a read that fails or takes long
//leaves the footer as it was: the message never waits for it, nor fails because of it
@Component
@RequiredArgsConstructor
public class DiscordHouseholdTransport implements HouseholdTransport {

    static final Duration RECIPIENTS_WITHIN = Duration.ofSeconds(5);

    private final DiscordBotService discordBotService;
    private final ObjectProvider<SmsRecipients> smsRecipients;

    @Override
    public String name() {
        return "discord";
    }

    @Override
    public Mono<Void> deliver(final HouseholdNotification notification, final HouseholdMessage message) {
        return withRecipients(message)
            .flatMap(discordBotService::sendHouseholdMessage)
            .retryWhen(DiscordDeliveryRetry.spec());
    }

    private Mono<HouseholdMessage> withRecipients(final HouseholdMessage message) {
        return Mono.justOrEmpty(smsRecipients.getIfAvailable())
            .flatMap(sms -> sms.recipients().timeout(RECIPIENTS_WITHIN))
            .filter(found -> !found.isEmpty())
            .map(found -> message.withFooter("SMS: " + found.stream()
                .map(SmsRecipients.Recipient::name)
                .collect(Collectors.joining(", "))))
            .onErrorResume(failure -> Mono.empty())
            .defaultIfEmpty(message);
    }
}
