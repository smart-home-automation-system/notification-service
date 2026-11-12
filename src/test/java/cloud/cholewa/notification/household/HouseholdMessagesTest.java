package cloud.cholewa.notification.household;

import cloud.cholewa.home.model.HouseholdEvent;
import cloud.cholewa.home.model.HouseholdNotification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.LocalDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

//The Polish words of the household channel, written out: they are the owner's call, made on a real
//message, and a change of them is a change of this test
class HouseholdMessagesTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 24, 19, 5);

    @Test
    void should_say_that_an_appliance_finished_and_needs_emptying() {
        final HouseholdMessage message = HouseholdMessages.of(notification("Dishwasher", HouseholdEvent.APPLIANCE_FINISHED, 0));

        assertThat(message.title()).isEqualTo("Zmywarka");
        assertThat(message.text()).isEqualTo("Zmywarka skończyła pracę - czas ją opróżnić.");
        assertThat(message.footer()).isEqualTo("24.10.2026 19:05");
        assertThat(message.color()).isEqualTo(HouseholdMessages.FINISHED_COLOR);
    }

    //the payload carries the number of the reminder, not how many there will be
    @Test
    void should_remind_with_the_number_of_the_reminder() {
        final HouseholdMessage message = HouseholdMessages.of(notification("Washing machine", HouseholdEvent.APPLIANCE_REMINDER, 2));

        assertThat(message.text()).isEqualTo("Pralka wciąż czeka na opróżnienie (przypomnienie 2).");
        assertThat(message.color()).isEqualTo(HouseholdMessages.REMINDER_COLOR);
    }

    @Test
    void should_thank_when_somebody_acknowledged() {
        final HouseholdMessage message = HouseholdMessages.of(notification("Dryer", HouseholdEvent.APPLIANCE_ACKNOWLEDGED, 3));

        assertThat(message.text()).isEqualTo("Suszarka opróżniona - dziękujemy!");
        assertThat(message.color()).isEqualTo(HouseholdMessages.ACKNOWLEDGED_COLOR);
    }

    //HAS-347: who acknowledged it - after the verb, so neither the case of the name nor a gender matters
    @Test
    void should_name_who_acknowledged_it() {
        assertThat(HouseholdMessages.of(acknowledged("Dryer", "Anna")).text())
            .isEqualTo("Suszarka opróżniona - potwierdza Anna. Dziękujemy!");
        assertThat(HouseholdMessages.of(acknowledged("Coffee machine", "Jan")).text())
            .isEqualTo("Coffee machine - potwierdza Jan. Dziękujemy!");
    }

    //nobody known, or a blank name: the sentences without anybody
    @Test
    void should_thank_without_a_name_when_nobody_is_known() {
        assertThat(HouseholdMessages.of(acknowledged("Dryer", null)).text()).isEqualTo("Suszarka opróżniona - dziękujemy!");
        assertThat(HouseholdMessages.of(acknowledged("Dryer", "  ")).text()).isEqualTo("Suszarka opróżniona - dziękujemy!");
        assertThat(HouseholdMessages.of(acknowledged("Coffee machine", null)).text())
            .isEqualTo("Coffee machine - potwierdzone, dziękujemy.");
    }

    //only the acknowledgement names anybody
    @Test
    void should_name_nobody_in_the_other_events() {
        final HouseholdNotification finished = notification("Dryer", HouseholdEvent.APPLIANCE_FINISHED, 0).member("Anna");

        assertThat(HouseholdMessages.of(finished).text()).doesNotContain("Anna");
    }

    //the registry names them in English; a Polish name, or another case, reads the same
    @ParameterizedTest
    @CsvSource({
        "Washing machine, Pralka", "WASHING MACHINE, Pralka", "' pralka ', Pralka",
        "Dryer, Suszarka", "suszarka, Suszarka",
        "Dishwasher, Zmywarka", "Zmywarka, Zmywarka"})
    void should_name_the_three_appliances_in_polish(final String registryName, final String polish) {
        assertThat(HouseholdMessages.of(notification(registryName, HouseholdEvent.APPLIANCE_FINISHED, 0)).title())
            .isEqualTo(polish);
    }

    //a device the service does not know keeps its name, in sentences without a gendered verb
    @Test
    void should_word_another_device_without_a_gendered_verb() {
        final String name = "Coffee machine";

        assertThat(HouseholdMessages.of(notification(name, HouseholdEvent.APPLIANCE_FINISHED, 0)))
            .extracting(HouseholdMessage::title, HouseholdMessage::text)
            .containsExactly(name, "Koniec pracy: Coffee machine - wymaga opróżnienia.");
        assertThat(HouseholdMessages.of(notification(name, HouseholdEvent.APPLIANCE_REMINDER, 2)).text())
            .isEqualTo("Przypomnienie 2: Coffee machine nadal czeka na opróżnienie.");
        assertThat(HouseholdMessages.of(notification(name, HouseholdEvent.APPLIANCE_ACKNOWLEDGED, 3)).text())
            .isEqualTo("Coffee machine - potwierdzone, dziękujemy.");
    }

    //every event of an appliance has words, a title and a footer - the smog has its own tests above (HAS-430)
    @ParameterizedTest
    @EnumSource(value = HouseholdEvent.class, names = {"SMOG_WARNING", "SMOG_OVER"}, mode = EnumSource.Mode.EXCLUDE)
    void should_word_every_event(final HouseholdEvent event) {
        final HouseholdMessage message = HouseholdMessages.of(notification("Dishwasher", event, 1));

        assertThat(message.title()).isEqualTo("Zmywarka");
        assertThat(message.text()).contains("Zmywarka").isNotBlank();
        assertThat(message.footer()).isEqualTo("24.10.2026 19:05");
    }

    //and the same for a device without a Polish name
    @ParameterizedTest
    @EnumSource(value = HouseholdEvent.class, names = {"SMOG_WARNING", "SMOG_OVER"}, mode = EnumSource.Mode.EXCLUDE)
    void should_word_every_event_of_another_device(final HouseholdEvent event) {
        final HouseholdMessage message = HouseholdMessages.of(notification("Coffee machine", event, 1));

        assertThat(message.title()).isEqualTo("Coffee machine");
        assertThat(message.text()).contains("Coffee machine").isNotBlank();
        assertThat(message.footer()).isEqualTo("24.10.2026 19:05");
    }

    @Test
    void should_tell_the_three_events_apart_by_color() {
        assertThat(Set.of(
            HouseholdMessages.FINISHED_COLOR, HouseholdMessages.REMINDER_COLOR, HouseholdMessages.ACKNOWLEDGED_COLOR
        )).hasSize(3);
    }

    //HAS-430: the smog outside, worded with its particles - bad in amber
    @Test
    void should_warn_about_smog_with_its_particles() {
        final HouseholdMessage message = HouseholdMessages.of(smog(HouseholdEvent.SMOG_WARNING, 112, 78));

        assertThat(message.title()).isEqualTo("Air quality sensor");
        assertThat(message.text()).isEqualTo("Smog na zewnątrz: PM10 112 µg/m³, PM2.5 78 µg/m³ - poziom zły. Zamknij okna i nie wietrz.");
        assertThat(message.footer()).isEqualTo("24.10.2026 19:05");
        assertThat(message.color()).isEqualTo(HouseholdMessages.SMOG_BAD_COLOR);
    }

    //very bad by the GIOS index: above 150 of PM10 or above 110 of PM2.5 - in red
    @ParameterizedTest
    @CsvSource({"151, 80", "120, 111"})
    void should_call_the_smog_very_bad_above_the_index(final int pm10, final int pm25) {
        final HouseholdMessage message = HouseholdMessages.of(smog(HouseholdEvent.SMOG_WARNING, pm10, pm25));

        assertThat(message.text()).contains("poziom bardzo zły");
        assertThat(message.color()).isEqualTo(HouseholdMessages.SMOG_VERY_BAD_COLOR);
    }

    //a message without the numbers is still worded
    @Test
    void should_word_the_smog_without_the_numbers() {
        assertThat(HouseholdMessages.of(smog(HouseholdEvent.SMOG_WARNING, null, null)).text())
            .isEqualTo("Smog na zewnątrz - poziom zły. Zamknij okna i nie wietrz.");
        assertThat(HouseholdMessages.of(smog(HouseholdEvent.SMOG_WARNING, null, 60)).text())
            .isEqualTo("Smog na zewnątrz: PM2.5 60 µg/m³ - poziom zły. Zamknij okna i nie wietrz.");
        assertThat(HouseholdMessages.of(smog(HouseholdEvent.SMOG_OVER, null, null)).text())
            .isEqualTo("Powietrze na zewnątrz znów w normie.");
    }

    @Test
    void should_say_when_the_air_is_fine_again() {
        final HouseholdMessage message = HouseholdMessages.of(smog(HouseholdEvent.SMOG_OVER, 35, 20));

        assertThat(message.text()).isEqualTo("Powietrze na zewnątrz znów w normie (PM10 35 µg/m³).");
        assertThat(message.color()).isEqualTo(HouseholdMessages.ACKNOWLEDGED_COLOR);
    }

    //the smog is never texted - the rule the transports ask
    @Test
    void should_tell_the_smog_apart_from_the_appliances() {
        assertThat(HouseholdMessages.isSmog(smog(HouseholdEvent.SMOG_WARNING, 1, 1))).isTrue();
        assertThat(HouseholdMessages.isSmog(smog(HouseholdEvent.SMOG_OVER, 1, 1))).isTrue();
        assertThat(HouseholdMessages.isSmog(notification("Dryer", HouseholdEvent.APPLIANCE_FINISHED, 0))).isFalse();
    }

    private static HouseholdNotification smog(final HouseholdEvent event, final Integer pm10, final Integer pm25) {
        return notification("Air quality sensor", event, 0).pm10(pm10).pm25(pm25);
    }

    private static HouseholdNotification acknowledged(final String subject, final String member) {
        return notification(subject, HouseholdEvent.APPLIANCE_ACKNOWLEDGED, 2).member(member);
    }

    private static HouseholdNotification notification(final String subject, final HouseholdEvent event, final int reminder) {
        return HouseholdNotification.builder()
            .event(event).subject(subject).deviceId(64L).occurredAt(AT).reminder(reminder).build();
    }
}
