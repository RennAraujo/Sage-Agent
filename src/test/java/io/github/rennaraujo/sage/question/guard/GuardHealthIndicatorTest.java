package io.github.rennaraujo.sage.question.guard;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

class GuardHealthIndicatorTest {

    @Test
    void passThroughIsReportedAsDisabledButUp() {
        Health health = new GuardHealthIndicator(new PassThroughGroundednessChecker()).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("nli", "disabled");
    }

    @Test
    void anyOtherCheckerIsReportedAsActive() {
        Health health = new GuardHealthIndicator((sentence, evidence) -> true).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("nli", "active");
    }

    @Test
    void reportsTimeoutAndSaturationCountsFromTheFactory() {
        try (AnswerGuardFactory factory = AnswerGuardFactory.citationsOnly()) {
            Health health = new GuardHealthIndicator(new PassThroughGroundednessChecker(), factory).health();

            assertThat(health.getDetails()).containsEntry("nliTimeouts", 0L).containsEntry("nliSaturated", 0L);
        }
    }
}
