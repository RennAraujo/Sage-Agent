package io.github.rennaraujo.sage.question.guard;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rennaraujo.sage.upload.SecretScanner;
import org.junit.jupiter.api.Test;
import reactor.core.scheduler.Scheduler;

class AnswerGuardFactoryTest {

    @Test
    void citationsOnlyFactoriesShareOneSchedulerThatOutlivesThem() {
        Scheduler shared;
        try (AnswerGuardFactory first = AnswerGuardFactory.citationsOnly();
                AnswerGuardFactory second = AnswerGuardFactory.citationsOnly()) {
            shared = first.scheduler();
            assertThat(second.scheduler()).isSameAs(shared);
        }

        // Fechar uma fábrica de conveniência não derruba o scheduler que as outras ainda usam.
        assertThat(shared.isDisposed()).isFalse();
    }

    @Test
    void productionFactoryDisposesItsOwnSchedulerOnClose() {
        AnswerGuardFactory factory = new AnswerGuardFactory(
                new PassThroughGroundednessChecker(), new SecretScanner(), new GuardProperties(0.5, 256, false));
        Scheduler own = factory.scheduler();

        factory.close();

        assertThat(own.isDisposed()).isTrue();
    }
}
