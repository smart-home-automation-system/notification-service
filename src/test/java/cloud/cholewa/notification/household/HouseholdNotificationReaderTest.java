package cloud.cholewa.notification.household;

import cloud.cholewa.home.model.HouseholdEvent;
import cloud.cholewa.home.model.HouseholdNotification;
import cloud.cholewa.home.model.RoomName;
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
             "reminder":0,"floor":"up"}""");

        assertThat(notification.getSubject()).isEqualTo("Pralka");
    }

    //HAS-462: the room of a fire alarm, the old spelling of hall up included
    @Test
    void should_read_the_room_of_a_fire_alarm() {
        assertThat(reader.read(fire("kitchen")).getRoom()).isEqualTo(RoomName.KITCHEN);
        assertThat(reader.read(fire("hall_up")).getRoom()).isEqualTo(RoomName.HALL_UP);
    }

    //a room a newer SDK added must not drop the alarm: it is read as no room
    @Test
    void should_read_a_room_this_version_does_not_know_as_no_room() {
        final HouseholdNotification notification = reader.read(fire("pantry"));

        assertThat(notification.getEvent()).isEqualTo(HouseholdEvent.FIRE_ALARM);
        assertThat(notification.getRoom()).isNull();
    }

    //nor an object: skipped whole, the properties after it still read
    @Test
    void should_skip_a_room_that_is_not_a_text() {
        final HouseholdNotification notification = reader.read("""
            {"event":"fire alarm","room":{"name":"kitchen"},"subject":"Smoke detector","deviceId":151,
             "occurredAt":"2026-11-20T19:15:00","reminder":0}""");

        assertThat(notification.getRoom()).isNull();
        assertThat(notification.getSubject()).isEqualTo("Smoke detector");
    }

    private static String fire(final String room) {
        return """
            {"event":"fire alarm","subject":"Smoke detector","deviceId":151,"occurredAt":"2026-11-20T19:15:00",
             "reminder":0,"room":"%s"}""".formatted(room);
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
