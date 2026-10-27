package io.github.rennaraujo.sage.search.embedding;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * multilingual-e5-small em ONNX Runtime (CPU). A sessão e o tokenizer são criados uma vez e compartilhados: o
 * {@code OrtSession.run} e o tokenizer (Rust, sem estado mutável depois de construído) aceitam chamadas concorrentes.
 *
 * <p>O e5 foi treinado com prefixos: {@code "query: "} na pergunta e {@code "passage: "} no trecho. Sem eles a
 * qualidade cai. A entrada é truncada em {@value #MAX_TOKENS} tokens, o limite do modelo. O vetor é a média dos
 * embeddings dos tokens ponderada pela attention mask (mean pooling), normalizada em L2.
 *
 * <p>Nada do texto passa por arquivo: o tokenizer lê só o {@code tokenizer.json}; as bibliotecas nativas são
 * extraídas para o cache do DJL e para o diretório temporário, e não contêm conteúdo de documento.
 */
public final class OnnxEmbeddingModel implements EmbeddingModel, AutoCloseable {

    /** Limite de posições do modelo. */
    static final int MAX_TOKENS = 512;

    private static final String QUERY_PREFIX = "query: ";
    private static final String PASSAGE_PREFIX = "passage: ";
    private static final String INPUT_IDS = "input_ids";
    private static final String ATTENTION_MASK = "attention_mask";
    private static final String TOKEN_TYPE_IDS = "token_type_ids";
    private static final String PROBE_TEXT = "dimensions probe";

    private final OrtEnvironment environment;
    private final OrtSession session;
    private final HuggingFaceTokenizer tokenizer;
    private final boolean needsTokenTypeIds;
    private final int dimensions;
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Abre a sessão e o tokenizer e mede a dimensão. Se qualquer passo falhar, o que já foi aberto (memória nativa) é
     * fechado antes de a exceção sair, porque o objeto nunca chega a quem poderia chamar {@link #close()}.
     */
    public OnnxEmbeddingModel(ModelFiles files) {
        OrtSession openedSession = null;
        HuggingFaceTokenizer openedTokenizer = null;
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
                    .optMaxLength(MAX_TOKENS)
                    .optPadding(false)
                    .build();
            this.tokenizer = openedTokenizer;
            this.dimensions = embed(PASSAGE_PREFIX + PROBE_TEXT).length;
        } catch (OrtException e) {
            closeQuietly(openedSession, openedTokenizer);
            throw new IllegalStateException("Falha ao abrir o modelo de embeddings", e);
        } catch (IOException e) {
            closeQuietly(openedSession, openedTokenizer);
            throw new UncheckedIOException("Falha ao abrir o tokenizer", e);
        } catch (RuntimeException e) {
            closeQuietly(openedSession, openedTokenizer);
            throw e;
        }
    }

    private static void closeQuietly(OrtSession session, HuggingFaceTokenizer tokenizer) {
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
    public float[] embedQuery(String text) {
        return embed(QUERY_PREFIX + text);
    }

    @Override
    public float[] embedPassage(String text) {
        return embed(PASSAGE_PREFIX + text);
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    private float[] embed(String prefixed) {
        if (closed.get()) {
            throw new IllegalStateException("O modelo de embeddings já foi fechado");
        }
        Encoding encoding = tokenizer.encode(prefixed);
        long[] ids = encoding.getIds();
        long[] mask = encoding.getAttentionMask();
        Map<String, OnnxTensor> inputs = new HashMap<>();
        try {
            inputs.put(INPUT_IDS, OnnxTensor.createTensor(environment, new long[][] {ids}));
            inputs.put(ATTENTION_MASK, OnnxTensor.createTensor(environment, new long[][] {mask}));
            if (needsTokenTypeIds) {
                inputs.put(TOKEN_TYPE_IDS, OnnxTensor.createTensor(environment, new long[][] {new long[ids.length]}));
            }
            try (OrtSession.Result result = session.run(inputs)) {
                float[][][] hidden = (float[][][]) result.get(0).getValue();
                return normalize(meanPool(hidden[0], mask));
            }
        } catch (OrtException e) {
            throw new IllegalStateException("Falha ao gerar o embedding", e);
        } finally {
            inputs.values().forEach(OnnxTensor::close);
        }
    }

    private static float[] meanPool(float[][] tokens, long[] mask) {
        float[] sum = new float[tokens[0].length];
        long count = 0;
        for (int t = 0; t < tokens.length; t++) {
            if (mask[t] == 0) {
                continue;
            }
            count++;
            for (int d = 0; d < sum.length; d++) {
                sum[d] += tokens[t][d];
            }
        }
        float divisor = Math.max(count, 1);
        for (int d = 0; d < sum.length; d++) {
            sum[d] /= divisor;
        }
        return sum;
    }

    private static float[] normalize(float[] v) {
        double squares = 0;
        for (float x : v) {
            squares += (double) x * x;
        }
        double norm = Math.sqrt(squares);
        if (norm == 0) {
            return v;
        }
        float inverse = (float) (1 / norm);
        for (int i = 0; i < v.length; i++) {
            v[i] *= inverse;
        }
        return v;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            session.close();
        } catch (OrtException e) {
            throw new IllegalStateException("Falha ao fechar a sessão ONNX", e);
        } finally {
            tokenizer.close();
        }
    }
}
