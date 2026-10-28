package io.github.rennaraujo.sage.eval;

import ai.onnxruntime.OrtException;
import io.github.rennaraujo.sage.question.guard.IdentifierCheck;
import io.github.rennaraujo.sage.question.guard.NliFiles;
import io.github.rennaraujo.sage.question.guard.PremiseWindows;
import io.github.rennaraujo.sage.search.onnx.OnnxPairModel;
import io.github.rennaraujo.sage.search.onnx.OnnxPairModel.Truncate;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Lê a probabilidade de entailment (softmax) que o verificador de produção compara com {@code min-entailment}, para a
 * varredura de limiares não precisar de uma sessão ONNX por limiar. Faz o mesmo que {@code OnnxNliChecker}: trecho como
 * premissa, frase cortada como hipótese e rótulo de entailment lido do {@code id2label} do config.
 */
final class NliProbe implements AutoCloseable {

    private final OnnxPairModel model;
    private final int entailment;

    NliProbe(NliFiles files, int maxTokens) {
        this.model = new OnnxPairModel(files.model(), files.tokenizer(), maxTokens, Truncate.FIRST, "de NLI");
        this.entailment = entailmentIndex(files);
    }

    /** Maior probabilidade de entailment entre as janelas do trecho, como o {@code OnnxNliChecker} decide. */
    double entailment(String evidence, String sentence) {
        return entailment(evidence, sentence, PremiseWindows.WINDOW);
    }

    /** {@code window} 0 é o trecho inteiro como premissa (o que o guard fazia antes das janelas). */
    double entailment(String evidence, String sentence, int window) {
        if (!IdentifierCheck.allKnown(sentence, List.of(evidence))) {
            return 0.0;
        }
        if (PremiseWindows.copiedFrom(evidence, sentence)) {
            return 1.0;
        }
        try {
            String hypothesis = model.limit(sentence);
            List<String> windows = window == 0 ? List.of(evidence) : PremiseWindows.of(evidence, sentence, window);
            List<String> hypotheses = windows.stream().map(w -> hypothesis).toList();
            double best = 0;
            for (float[] logits : model.run(windows, hypotheses)) {
                best = Math.max(best, softmaxAt(logits));
            }
            return best;
        } catch (OrtException e) {
            throw new IllegalStateException(e);
        }
    }

    private double softmaxAt(float[] logits) {
        float max = logits[0];
        for (float logit : logits) {
            max = Math.max(max, logit);
        }
        double sum = 0;
        for (float logit : logits) {
            sum += Math.exp(logit - max);
        }
        return Math.exp(logits[entailment] - max) / sum;
    }

    @SuppressWarnings("unchecked")
    private static int entailmentIndex(NliFiles files) {
        try (InputStream in = Files.newInputStream(files.config())) {
            Map<String, Object> config = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
            Map<Object, Object> labels = (Map<Object, Object>) config.get("id2label");
            for (Map.Entry<Object, Object> entry : labels.entrySet()) {
                if ("entailment".equalsIgnoreCase(String.valueOf(entry.getValue()))) {
                    return Integer.parseInt(String.valueOf(entry.getKey()));
                }
            }
            throw new IllegalStateException("config sem entailment");
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        model.close();
    }
}
