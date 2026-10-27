package io.github.rennaraujo.sage.question.guard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class GuardPropertiesTest {

    @Test
    void nonPositiveTimeoutIsRejected() {
        assertThatThrownBy(() -> new GuardProperties(0.5, 256, false, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GuardProperties(0.5, 256, false, Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void concurrencyDefaultsToHalfTheCoresWithAFloorOfOne() {
        GuardProperties defaults = new GuardProperties(0.5, 256, false);

        assertThat(defaults.nliMaxConcurrency())
                .isEqualTo(Math.max(1, Runtime.getRuntime().availableProcessors() / 2));
        assertThat(defaults.nliTimeoutPerSentence()).isEqualTo(Duration.ofMillis(1500));
        assertThat(new GuardProperties(0.5, 256, false, Duration.ofSeconds(1), 3).nliMaxConcurrency())
                .isEqualTo(3);
    }
}
