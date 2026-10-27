package io.github.rennaraujo.sage.question.guard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rennaraujo.sage.search.embedding.ModelProperties;
import io.github.rennaraujo.sage.testsupport.ScratchDirs;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class GuardConfigTest {

    @Test
    void withoutModelFilesTheGuardDegradesToPassThrough() throws Exception {
        Path empty = ScratchDirs.create("sage-guard-config");
        try {
            GroundednessChecker checker = new GuardConfig()
                    .groundednessChecker(new ModelProperties(empty), new GuardProperties(0.5, 256, false));

            assertThat(checker).isInstanceOf(PassThroughGroundednessChecker.class);
            assertThat(checker.isSupported("qualquer frase", List.of("trecho"))).isTrue();
        } finally {
            ScratchDirs.delete(empty);
        }
    }

    @Test
    void passThroughStillRejectsSentencesWithoutEvidence() {
        assertThat(new PassThroughGroundednessChecker().isSupported("qualquer frase", List.of()))
                .isFalse();
    }

    @Test
    void requireNliFailsStartupWhenTheModelIsMissing() throws Exception {
        Path empty = ScratchDirs.create("sage-guard-config");
        try {
            GuardConfig config = new GuardConfig();
            ModelProperties models = new ModelProperties(empty);
            GuardProperties strict = new GuardProperties(0.5, 256, true);

            assertThatThrownBy(() -> config.groundednessChecker(models, strict))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("sage.guard.require-nli");
        } finally {
            ScratchDirs.delete(empty);
        }
    }
}
