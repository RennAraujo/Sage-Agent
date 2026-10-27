package io.github.rennaraujo.sage.search.rerank;

import ai.onnxruntime.OrtException;
import io.github.rennaraujo.sage.search.Chunk;
import io.github.rennaraujo.sage.search.ScoredChunk;
import io.github.rennaraujo.sage.search.embedding.ModelFiles;
import io.github.rennaraujo.sage.search.onnx.OnnxPairModel;
import io.github.rennaraujo.sage.search.onnx.OnnxPairModel.Truncate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Cross-encoder mmarco-mMiniLMv2-L12-H384 em ONNX Runtime (CPU). Cada par (pergunta, trecho) entra junto no modelo, que
 * devolve um logit; o score é {@code sigmoid(logit)}, em [0, 1]. Todos os pares de uma chamada vão num único lote,
 * preenchidos (padding) até o maior par do lote.
 *
 * <p>O trecho é {@code headingPath + "\n" + text}, o mesmo texto que vira embedding. Cada par é truncado em
 * {@code sage.rerank.max-tokens} (padrão 256, o modelo aceita até 512), cortando só o lado do trecho; a pergunta
 * é cortada antes, em no máximo metade do orçamento. A sessão, os tokenizers e o ciclo de vida são do
 * {@link OnnxPairModel}, compartilhado com o verificador de fundamentação.
 */
public final class OnnxReranker implements Reranker, AutoCloseable {

    private final OnnxPairModel model;

    /** Abre o modelo e pontua um par de teste, para um modelo errado falhar na partida e não na primeira pergunta. */
    public OnnxReranker(ModelFiles files, RerankProperties properties) {
        this.model = new OnnxPairModel(
                files.model(), files.tokenizer(), properties.maxTokens(), Truncate.SECOND, "de reranking");
    }

    @Override
    public List<ScoredChunk> rerank(String query, List<Chunk> candidates) {
        model.ensureOpen();
        if (candidates.isEmpty()) {
            return List.of();
        }
        String question = model.limit(query);
        List<String> queries = new ArrayList<>(candidates.size());
        List<String> passages = new ArrayList<>(candidates.size());
        for (Chunk chunk : candidates) {
            queries.add(question);
            passages.add(chunk.headingPath() + "\n" + chunk.text());
        }
        float[][] logits;
        try {
            logits = model.run(queries, passages);
        } catch (OrtException e) {
            throw new IllegalStateException("Falha ao pontuar os candidatos", e);
        }
        List<ScoredChunk> scored = new ArrayList<>(candidates.size());
        for (int i = 0; i < logits.length; i++) {
            scored.add(new ScoredChunk(candidates.get(i), sigmoid(logits[i][0])));
        }
        scored.sort(Comparator.comparingDouble(ScoredChunk::score).reversed());
        return List.copyOf(scored);
    }

    private static double sigmoid(float logit) {
        return 1.0 / (1.0 + Math.exp(-logit));
    }

    @Override
    public void close() {
        model.close();
    }
}
