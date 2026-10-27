package io.github.rennaraujo.sage.question.guard;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rennaraujo.sage.upload.SecretScanner;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** O guard inteiro com o NLI de verdade: a frase que o trecho sustenta passa, a que ele contradiz é descartada. */
@Tag("models")
@EnabledIf("modelsPresent")
class AnswerGuardModelsTest {

    private static final Logger LOG = LoggerFactory.getLogger(AnswerGuardModelsTest.class);
    private static final List<String> EVIDENCE = List.of("Stream.toList() returns an unmodifiable List.");

    private static OnnxNliChecker nli;
    private static AnswerGuardFactory factory;

    static boolean modelsPresent() {
        return files().available();
    }

    private static NliFiles files() {
        return NliFiles.inDir(Path.of(System.getProperty("sage.models.dir", "models")));
    }

    @BeforeAll
    static void load() {
        GuardProperties properties = new GuardProperties(0.5, 256, false);
        nli = new OnnxNliChecker(files(), properties);
        factory = new AnswerGuardFactory(nli, new SecretScanner(), properties);
    }

    @AfterAll
    static void unload() {
        if (nli != null) {
            nli.close();
        }
    }

    @Test
    void entailedSentencePassesAndContradictedOneIsDropped() {
        AnswerGuard guard = factory.create(EVIDENCE, false, false);

        List<String> out = run(
                guard,
                "O método toList retorna uma lista imutável [1]. O método toList retorna uma lista mutável [1].");

        assertThat(out).containsExactly("O método toList retorna uma lista imutável [1].");
        assertThat(guard.dropped()).isEqualTo(1);
        assertThat(guard.cited()).containsExactly(1);
    }

    @Test
    void perSentenceLatencyThroughTheGuardIsLogged() {
        run(factory.create(EVIDENCE, false, false), "O método toList retorna uma lista imutável [1].");

        long start = System.nanoTime();
        AnswerGuard guard = factory.create(EVIDENCE, false, false);
        run(guard, "O método toList retorna uma lista imutável [1]. Os elementos vêm na ordem do stream [1].");
        long millis = (System.nanoTime() - start) / 1_000_000;

        LOG.info("guard com NLI: {} ms para 2 frases citando 1 trecho curto", millis);
        assertThat(millis).isLessThan(10000);
    }

    private static List<String> run(AnswerGuard guard, String text) {
        List<String> out = new ArrayList<>(guard.accept(text));
        out.addAll(guard.finish());
        return out;
    }
}
