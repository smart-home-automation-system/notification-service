package cloud.cholewa.notification.model;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationLevelTest {

    @ParameterizedTest
    @CsvSource({"error, ERROR", "WARN, WARN", "Info, INFO", "' warn ', WARN"})
    void should_read_level_in_any_case(final String name, final NotificationLevel expected) {
        assertThat(NotificationLevel.of(name, NotificationLevel.ERROR)).isEqualTo(expected);
    }

    //a publisher with a typo still gets its notification delivered
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"critical", "warning", "0"})
    void should_fall_back_when_level_is_missing_or_unknown(final String name) {
        assertThat(NotificationLevel.of(name, NotificationLevel.INFO)).isEqualTo(NotificationLevel.INFO);
    }
}
