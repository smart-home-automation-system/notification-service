package cloud.cholewa.notification.household;

import cloud.cholewa.home.model.HouseholdEvent;
import cloud.cholewa.home.model.HouseholdNotification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

//The Polish words of the household channel, written out: they are the owner's call, made on a real
//message, and a change of them is a change of this test
class HouseholdMessagesTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 24, 19, 5);

    @Test
    void should_say_that_an_appliance_finished_and_needs_emptying() {
        final HouseholdMessage message = HouseholdMessages.of(notification(HouseholdEvent.APPLIANCE_FINISHED, 0));

        assertThat(message.title()).isEqualTo("Zmywarka");
        assertThat(message.text()).isEqualTo("Koniec pracy: Zmywarka - wymaga opróżnienia.");
        assertThat(message.footer()).isEqualTo("24.10.2026 19:05");
        assertThat(message.color()).isEqualTo(HouseholdMessages.FINISHED_COLOR);
    }

    //the payload carries the number of the reminder, not how many there will be
    @Test
    void should_remind_with_the_number_of_the_reminder() {
        final HouseholdMessage message = HouseholdMessages.of(notification(HouseholdEvent.APPLIANCE_REMINDER, 2));

        assertThat(message.text()).isEqualTo("Przypomnienie 2: Zmywarka nadal czeka na opróżnienie.");
        assertThat(message.color()).isEqualTo(HouseholdMessages.REMINDER_COLOR);
    }

    @Test
    void should_thank_when_somebody_acknowledged() {
        final HouseholdMessage message = HouseholdMessages.of(notification(HouseholdEvent.APPLIANCE_ACKNOWLEDGED, 3));

        assertThat(message.text()).isEqualTo("Zmywarka - potwierdzone, dziękujemy.");
        assertThat(message.color()).isEqualTo(HouseholdMessages.ACKNOWLEDGED_COLOR);
    }

    //every event of the SDK has words, a title and a footer
    @ParameterizedTest
    @EnumSource(HouseholdEvent.class)
    void should_word_every_event(final HouseholdEvent event) {
        final HouseholdMessage message = HouseholdMessages.of(notification(event, 1));

        assertThat(message.title()).isEqualTo("Zmywarka");
        assertThat(message.text()).contains("Zmywarka").isNotBlank();
        assertThat(message.footer()).isEqualTo("24.10.2026 19:05");
    }

    @Test
    void should_tell_the_three_events_apart_by_color() {
        assertThat(java.util.Set.of(
            HouseholdMessages.FINISHED_COLOR, HouseholdMessages.REMINDER_COLOR, HouseholdMessages.ACKNOWLEDGED_COLOR
        )).hasSize(3);
    }

    private static HouseholdNotification notification(final HouseholdEvent event, final int reminder) {
        return HouseholdNotification.builder()
            .event(event).subject("Zmywarka").deviceId(64L).occurredAt(AT).reminder(reminder).build();
    }
}
