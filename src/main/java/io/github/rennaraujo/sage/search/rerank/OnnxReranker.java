package io.github.rennaraujo.sage.search.rerank;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.util.PairList;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import io.github.rennaraujo.sage.search.Chunk;
import io.github.rennaraujo.sage.search.ScoredChunk;
import io.github.rennaraujo.sage.search.embedding.ModelFiles;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cross-encoder mmarco-mMiniLMv2-L12-H384 em ONNX Runtime (CPU). Cada par (pergunta, trecho) entra junto no modelo, que
 * devolve um logit; o score é {@code sigmoid(logit)}, em [0, 1]. Todos os pares de uma chamada vão num único lote,
 * preenchidos (padding) até o maior par do lote.
 *
 * <p>O trecho é {@code headingPath + "\n" + text}, o mesmo texto que vira embedding. Cada par é truncado em
 * {@code sage.rerank.max-tokens} (padrão 256, o modelo aceita até 512), cortando só o lado do trecho; a pergunta
 * é cortada antes, em no máximo metade do orçamento. A sessão e o tokenizer são criados uma vez e compartilhados, como
 * em {@code OnnxEmbeddingModel}.
 */
public final class OnnxReranker implements Reranker, AutoCloseable {

    private static final String INPUT_IDS = "input_ids";
    private static final String ATTENTION_MASK = "attention_mask";
    private static final String TOKEN_TYPE_IDS = "token_type_ids";
    private static final String PROBE_QUERY = "probe";
    private static final String PROBE_PASSAGE = "probe";
    /** Tokens especiais que o par acrescenta (início, dois separadores e fim). */
    private static final int SPECIAL_TOKENS = 4;

    private final OrtEnvironment environment;
    private final OrtSession session;
    private final HuggingFaceTokenizer tokenizer;
    /** Segundo tokenizer, sem truncamento, só para medir a pergunta: o do par falha ao codificar texto sozinho. */
    private final HuggingFaceTokenizer counter;

    private final boolean needsTokenTypeIds;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final int maxQuestionTokens;

    /**
     * Abre a sessão e o tokenizer e pontua um par de teste, para um modelo errado falhar na partida e não na primeira
     * pergunta. Se qualquer passo falhar, o que já foi aberto (memória nativa) é fechado antes de a exceção sair.
     */
    public OnnxReranker(ModelFiles files, RerankProperties properties) {
        this.maxQuestionTokens = Math.max(1, properties.maxTokens() / 2 - SPECIAL_TOKENS);
        OrtSession openedSession = null;
        HuggingFaceTokenizer openedTokenizer = null;
        HuggingFaceTokenizer openedCounter = null;
        try {
            this.environment = OrtEnvironment.getEnvironment();
            try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
                openedSession = environment.createSession(files.model().toString(), options);
            }
            this.session = openedSession;
            this.needsTokenTypeIds = openedSession.getInputNames().contains(TOKEN_TYPE_IDS);
            openedTokenizer = HuggingFaceTokenizer.builder()
                    .optTokenizerPath(files.tokenizer())
                    .optTruncation(true)
                    .optMaxLength(properties.maxTokens())
                    .optTruncateSecondOnly()
                    .optPadding(true)
                    .build();
            this.tokenizer = openedTokenizer;
            openedCounter = HuggingFaceTokenizer.builder()
                    .optTokenizerPath(files.tokenizer())
                    .optTruncation(false)
                    .optPadding(false)
                    .build();
            this.counter = openedCounter;
            logits(List.of(PROBE_QUERY), List.of(PROBE_PASSAGE));
        } catch (OrtException e) {
            closeQuietly(openedSession, openedTokenizer, openedCounter);
            throw new IllegalStateException("Falha ao abrir o modelo de reranking", e);
        } catch (IOException e) {
            closeQuietly(openedSession, openedTokenizer, openedCounter);
            throw new UncheckedIOException("Falha ao abrir o tokenizer do reranker", e);
        } catch (RuntimeException e) {
            closeQuietly(openedSession, openedTokenizer, openedCounter);
            throw e;
        }
    }

    private static void closeQuietly(OrtSession session, HuggingFaceTokenizer tokenizer, HuggingFaceTokenizer counter) {
        if (counter != null) {
            counter.close();
        }
        if (tokenizer != null) {
            tokenizer.close();
        }
        if (session != null) {
            try {
                session.close();
            } catch (OrtException ignored) {
                // melhor esforço: a exceção original é a que importa
            }
        }
    }

    @Override
    public List<ScoredChunk> rerank(String query, List<Chunk> candidates) {
        if (closed.get()) {
            throw new IllegalStateException("O reranker já foi fechado");
        }
        if (candidates.isEmpty()) {
            return List.of();
        }
        String question = limitQuestion(query);
        List<String> queries = new ArrayList<>(candidates.size());
        List<String> passages = new ArrayList<>(candidates.size());
        for (Chunk chunk : candidates) {
            queries.add(question);
            passages.add(chunk.headingPath() + "\n" + chunk.text());
        }
        float[] logits;
        try {
            logits = logits(queries, passages);
        } catch (OrtException e) {
            throw new IllegalStateException("Falha ao pontuar os candidatos", e);
        }
        List<ScoredChunk> scored = new ArrayList<>(candidates.size());
        for (int i = 0; i < logits.length; i++) {
            scored.add(new ScoredChunk(candidates.get(i), sigmoid(logits[i])));
        }
        scored.sort(Comparator.comparingDouble(ScoredChunk::score).reversed());
        return List.copyOf(scored);
    }

    /**
     * Corta a pergunta em no máximo metade do orçamento de tokens. Sem isso uma pergunta que sozinha enche o orçamento
     * deixa o tokenizer do par sem espaço para o trecho, e com truncamento só do segundo lado ele lança exceção.
     * Cortar por caracteres e reconferir converge em poucas voltas, porque cada volta reduz na proporção do excesso.
     */
    private String limitQuestion(String query) {
        String cut = query;
        int tokens = countTokens(cut);
        while (tokens > maxQuestionTokens && cut.length() > 1) {
            int keep =
                    Math.max(1, Math.min(cut.length() - 1, (int) ((long) cut.length() * maxQuestionTokens / tokens)));
            if (Character.isHighSurrogate(cut.charAt(keep - 1)) && keep > 1) {
                keep--;
            }
            cut = cut.substring(0, keep);
            tokens = countTokens(cut);
        }
        return cut;
    }

    private int countTokens(String text) {
        return counter.encode(text, false, false).getIds().length;
    }

    private float[] logits(List<String> queries, List<String> passages) throws OrtException {
        Encoding[] encodings = tokenizer.batchEncode(new PairList<>(queries, passages));
        int batch = encodings.length;
        long[][] ids = new long[batch][];
        long[][] mask = new long[batch][];
        long[][] typeIds = new long[batch][];
        for (int i = 0; i < batch; i++) {
            ids[i] = encodings[i].getIds();
            mask[i] = encodings[i].getAttentionMask();
            typeIds[i] = encodings[i].getTypeIds();
        }
        Map<String, OnnxTensor> inputs = new HashMap<>();
        try {
            inputs.put(INPUT_IDS, OnnxTensor.createTensor(environment, ids));
            inputs.put(ATTENTION_MASK, OnnxTensor.createTensor(environment, mask));
            if (needsTokenTypeIds) {
                inputs.put(TOKEN_TYPE_IDS, OnnxTensor.createTensor(environment, typeIds));
            }
            try (OrtSession.Result result = session.run(inputs)) {
                float[][] out = (float[][]) result.get(0).getValue();
                float[] logits = new float[batch];
                for (int i = 0; i < batch; i++) {
                    logits[i] = out[i][0];
                }
                return logits;
            }
        } finally {
            inputs.values().forEach(OnnxTensor::close);
        }
    }

    private static double sigmoid(float logit) {
        return 1.0 / (1.0 + Math.exp(-logit));
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            session.close();
        } catch (OrtException e) {
            throw new IllegalStateException("Falha ao fechar a sessão ONNX do reranker", e);
        } finally {
            tokenizer.close();
            counter.close();
        }
    }
}
