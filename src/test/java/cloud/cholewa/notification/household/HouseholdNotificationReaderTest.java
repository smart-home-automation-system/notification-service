package cloud.cholewa.notification.household;

import cloud.cholewa.home.model.HouseholdEvent;
import cloud.cholewa.home.model.HouseholdNotification;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

//The JSON of the household queue into the SDK model, with Jackson 3 as Spring Boot configures it
//(unknown properties ignored) and the bounds of the model checked
class HouseholdNotificationReaderTest {

    private static ValidatorFactory validatorFactory;
    private static HouseholdNotificationReader reader;

    @BeforeAll
    static void setUp() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        final Validator validator = validatorFactory.getValidator();
        reader = new HouseholdNotificationReader(JsonMapper.builder().build(), validator);
    }

    @AfterAll
    static void tearDown() {
        validatorFactory.close();
    }

    @Test
    void should_read_what_appliance_service_publishes() {
        final HouseholdNotification notification = reader.read("""
            {"event":"appliance reminder","subject":"Zmywarka","deviceId":64,"occurredAt":"2026-10-24T19:40:00",
             "reminder":1,"acknowledgePath":"/room"}""");

        assertThat(notification.getEvent()).isEqualTo(HouseholdEvent.APPLIANCE_REMINDER);
        assertThat(notification.getSubject()).isEqualTo("Zmywarka");
        assertThat(notification.getDeviceId()).isEqualTo(64L);
        assertThat(notification.getOccurredAt()).isEqualTo(LocalDateTime.of(2026, 10, 24, 19, 40));
        assertThat(notification.getReminder()).isEqualTo(1);
        assertThat(notification.getAcknowledgePath()).isEqualTo("/room");
    }

    //a newer publisher may send more: what the model does not know is ignored, not refused
    @Test
    void should_ignore_a_property_the_model_does_not_know() {
        final HouseholdNotification notification = reader.read("""
            {"event":"appliance finished","subject":"Pralka","deviceId":65,"occurredAt":"2026-10-24T08:00:00",
             "reminder":0,"room":"bathroom"}""");

        assertThat(notification.getSubject()).isEqualTo("Pralka");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Pralka skończyła",
        "{",
        "[]",
        "{\"event\":\"appliance exploded\",\"subject\":\"Pralka\",\"deviceId\":1,\"occurredAt\":\"2026-10-24T08:00:00\",\"reminder\":0}",
        "{\"event\":\"appliance finished\",\"subject\":\"Pralka\",\"deviceId\":1,\"occurredAt\":\"yesterday\",\"reminder\":0}"
    })
    void should_refuse_a_payload_that_is_not_the_model(final String payload) {
        assertThatThrownBy(() -> reader.read(payload))
            .isInstanceOf(InvalidHouseholdNotificationException.class)
            .hasMessageStartingWith("not a household notification");
    }

    @Test
    void should_refuse_an_empty_payload() {
        assertThatThrownBy(() -> reader.read("null"))
            .isInstanceOf(InvalidHouseholdNotificationException.class)
            .hasMessage("an empty payload");
    }

    //the bounds of the model, named by their properties
    @Test
    void should_refuse_a_notification_out_of_the_bounds_of_the_model() {
        assertThatThrownBy(() -> reader.read("""
            {"event":"appliance finished","subject":"Pr","deviceId":0,"reminder":11}"""))
            .isInstanceOf(InvalidHouseholdNotificationException.class)
            .hasMessageContaining("deviceId")
            .hasMessageContaining("occurredAt")
            .hasMessageContaining("reminder")
            .hasMessageContaining("subject");
    }

    //the path becomes a link one day: a host or a protocol-relative path is not a household's
    @Test
    void should_refuse_an_acknowledge_path_that_is_no_path_of_the_application() {
        assertThatThrownBy(() -> reader.read("""
            {"event":"appliance finished","subject":"Pralka","deviceId":1,"occurredAt":"2026-10-24T08:00:00",
             "reminder":0,"acknowledgePath":"//evil.example"}"""))
            .isInstanceOf(InvalidHouseholdNotificationException.class)
            .hasMessageContaining("acknowledgePath");
    }
}
