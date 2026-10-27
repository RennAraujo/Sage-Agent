package io.github.rennaraujo.sage.question.guard;

import ai.onnxruntime.OrtException;
import io.github.rennaraujo.sage.search.onnx.OnnxPairModel;
import io.github.rennaraujo.sage.search.onnx.OnnxPairModel.Truncate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Verificação de fundamentação com um cross-encoder de NLI (mDeBERTa-v3-base-xnli-multilingual-nli-2mil7) em ONNX
 * Runtime (CPU). Cada trecho citado é uma premissa e a frase da resposta é a hipótese; as saídas são logits de três
 * rótulos. A frase é sustentada se, para algum trecho, a probabilidade (softmax) de "entailment" é ao menos
 * {@code sage.guard.min-entailment}. Os trechos de uma chamada vão num único lote.
 *
 * <p>A ordem dos rótulos vem do {@code id2label} do config do modelo, não de uma suposição: a ordem muda de modelo
 * para modelo. Cada par é truncado em {@code sage.guard.nli-max-tokens}, cortando só a premissa (primeiro lado do par);
 * a hipótese é cortada antes, em no máximo metade do orçamento. Sessão, tokenizers, truncamento e ciclo de vida são do
 * {@link OnnxPairModel}, o mesmo que o reranker usa.
 */
public final class OnnxNliChecker implements GroundednessChecker, AutoCloseable {

    private static final String ENTAILMENT = "entailment";
    private static final String ID2LABEL = "id2label";
    private static final String PROBE = "probe";

    private final OnnxPairModel model;
    private final int entailmentIndex;
    private final int labelCount;
    private final double minEntailment;

    /**
     * Lê os rótulos, abre o modelo e pontua um par de teste, para um modelo errado falhar na partida e não na primeira
     * resposta. Se a conferência final falhar, o modelo já aberto (memória nativa) é fechado antes de a exceção sair.
     */
    public OnnxNliChecker(NliFiles files, GuardProperties properties) {
        this.minEntailment = properties.minEntailment();
        Map<Integer, String> labels = readLabels(files);
        this.labelCount = labels.size();
        this.entailmentIndex = indexOfEntailment(labels);
        this.model = new OnnxPairModel(
                files.model(), files.tokenizer(), properties.nliMaxTokens(), Truncate.FIRST, "de NLI");
        try {
            entailment(List.of(PROBE), PROBE);
        } catch (OrtException e) {
            model.close();
            throw new IllegalStateException("Falha ao abrir o modelo de NLI", e);
        } catch (RuntimeException e) {
            model.close();
            throw e;
        }
    }

    private static Map<Integer, String> readLabels(NliFiles files) {
        JsonNode node;
        try {
            node = JsonMapper.builder().build().readTree(files.config().toFile());
        } catch (RuntimeException e) {
            throw new IllegalStateException("Config do modelo de NLI ilegível", e);
        }
        JsonNode id2label = node.get(ID2LABEL);
        if (id2label == null || !id2label.isObject() || id2label.size() == 0) {
            throw new IllegalStateException("Config do modelo de NLI sem id2label");
        }
        Map<Integer, String> labels = new HashMap<>();
        Map<String, Integer> seen = new HashMap<>();
        for (Map.Entry<String, JsonNode> entry : id2label.properties()) {
            int index;
            try {
                index = Integer.parseInt(entry.getKey());
            } catch (NumberFormatException e) {
                throw new IllegalStateException("id2label do modelo de NLI com índice inválido", e);
            }
            String label = entry.getValue().asString().toLowerCase(Locale.ROOT);
            if (seen.put(label, index) != null) {
                throw new IllegalStateException("id2label do modelo de NLI com rótulo duplicado");
            }
            labels.put(index, label);
        }
        return labels;
    }

    private static int indexOfEntailment(Map<Integer, String> labels) {
        return labels.entrySet().stream()
                .filter(e -> ENTAILMENT.equals(e.getValue()))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("O modelo de NLI não tem o rótulo entailment"));
    }

    @Override
    public boolean isSupported(String sentence, List<String> evidence) {
        model.ensureOpen();
        List<String> premises = new ArrayList<>(evidence.size());
        for (String text : evidence) {
            if (!text.isBlank()) {
                premises.add(text);
            }
        }
        if (premises.isEmpty()) {
            return false;
        }
        if (sentence.isBlank()) {
            return true;
        }
        try {
            for (double probability : entailment(premises, model.limit(sentence))) {
                if (probability >= minEntailment) {
                    return true;
                }
            }
            return false;
        } catch (OrtException e) {
            throw new IllegalStateException("Falha ao verificar a fundamentação", e);
        }
    }

    /** Probabilidade de entailment (softmax) de cada premissa contra a mesma hipótese. */
    private double[] entailment(List<String> premises, String hypothesis) throws OrtException {
        List<String> hypotheses = new ArrayList<>(premises.size());
        for (int i = 0; i < premises.size(); i++) {
            hypotheses.add(hypothesis);
        }
        float[][] logits = model.run(premises, hypotheses);
        double[] probabilities = new double[logits.length];
        for (int i = 0; i < logits.length; i++) {
            if (logits[i].length != labelCount) {
                throw new IllegalStateException("A saída do modelo de NLI não bate com os rótulos do config");
            }
            probabilities[i] = softmaxAt(logits[i], entailmentIndex);
        }
        return probabilities;
    }

    private static double softmaxAt(float[] logits, int index) {
        float max = logits[0];
        for (float logit : logits) {
            max = Math.max(max, logit);
        }
        double sum = 0;
        for (float logit : logits) {
            sum += Math.exp(logit - max);
        }
        return Math.exp(logits[index] - max) / sum;
    }

    @Override
    public void close() {
        model.close();
    }
}
