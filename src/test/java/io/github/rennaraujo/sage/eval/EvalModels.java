package io.github.rennaraujo.sage.eval;

import io.github.rennaraujo.sage.question.guard.NliFiles;
import io.github.rennaraujo.sage.search.embedding.ModelFiles;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;

/**
 * Presença dos modelos ONNX para as avaliações. No {@code verify} padrão a falta dos modelos pula o teste; com
 * {@code -Peval} ({@code sage.latency.strict}) ela é falha, porque uma avaliação que pula em silêncio não avalia nada.
 */
final class EvalModels {

    private EvalModels() {}

    static boolean strict() {
        return Boolean.getBoolean("sage.latency.strict");
    }

    static Path dir() {
        return Path.of(System.getProperty("sage.models.dir", "models"));
    }

    /** Condição de {@code @EnabledIf}: com {@code -Peval} vale sempre, e o {@link EvalStack} falha sem modelo. */
    static boolean embeddingsAndReranker() {
        return strict()
                || (ModelFiles.inDir(dir()).available()
                        && ModelFiles.rerankerInDir(dir()).available());
    }

    /** Pula (padrão) ou falha (-Peval) quando o modelo de NLI não está no disco. */
    static void requireNli() {
        boolean present = NliFiles.inDir(dir()).available();
        if (strict() && !present) {
            throw new IllegalStateException("-Peval exige o modelo de NLI em " + dir());
        }
        Assumptions.assumeTrue(present, "modelo de NLI ausente");
    }
}
