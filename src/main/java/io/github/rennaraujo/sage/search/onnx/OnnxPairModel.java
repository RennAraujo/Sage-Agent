package io.github.rennaraujo.sage.search.onnx;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.util.PairList;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Parte comum dos cross-encoders em ONNX Runtime (CPU): o reranker e o verificador de fundamentação. É dono da sessão,
 * do tokenizer de pares e de um segundo tokenizer só para contar tokens, e cuida do ciclo de vida (abrir com limpeza em
 * caso de falha, fechar uma vez só, recusar uso depois de fechado), de montar o lote de tensores e de fechá-los. Cada
 * chamador cuida da "cabeça": o que fazer com os logits (sigmoid de um logit, softmax de três).
 *
 * <p>Cada par é truncado em {@code maxTokens}, cortando só o lado indicado em {@link Truncate}. O outro lado precisa
 * ser cortado antes por {@link #limit(String)}, em no máximo metade do orçamento: o tokenizer em Rust lança exceção
 * (ou derruba a JVM) quando o lado que não pode ser cortado já estoura o limite. Sessão e tokenizers são criados uma
 * vez e compartilhados entre threads.
 */
public final class OnnxPairModel implements AutoCloseable {

    /** Qual lado do par é cortado pelo tokenizer quando o par passa de {@code maxTokens}. */
    public enum Truncate {
        FIRST,
        SECOND
    }

    private static final String INPUT_IDS = "input_ids";
    private static final String ATTENTION_MASK = "attention_mask";
    private static final String TOKEN_TYPE_IDS = "token_type_ids";
    private static final String PROBE = "probe";
    /** Margem para os tokens especiais que o par acrescenta (3 no DeBERTa, 4 em modelos XLM-R). */
    private static final int SPECIAL_TOKENS = 4;

    private final OrtEnvironment environment;
    private final OrtSession session;
    private final HuggingFaceTokenizer tokenizer;
    /** Segundo tokenizer, sem truncamento, só para medir o texto: o do par falha ao codificar texto sozinho. */
    private final HuggingFaceTokenizer counter;

    private final boolean needsTokenTypeIds;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final int sideBudget;

    /**
     * Abre a sessão e os tokenizers e roda um par de teste, para um modelo errado falhar na partida e não no primeiro
     * uso. Se qualquer passo falhar, o que já foi aberto (memória nativa) é fechado antes de a exceção sair.
     *
     * @param name como o modelo aparece nas mensagens de erro (por exemplo "de reranking")
     */
    public OnnxPairModel(Path model, Path tokenizerFile, int maxTokens, Truncate truncate, String name) {
        this.sideBudget = Math.max(1, maxTokens / 2 - SPECIAL_TOKENS);
        OrtSession openedSession = null;
        HuggingFaceTokenizer openedTokenizer = null;
        HuggingFaceTokenizer openedCounter = null;
        try {
            this.environment = OrtEnvironment.getEnvironment();
            try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
                openedSession = environment.createSession(model.toString(), options);
            }
            this.session = openedSession;
            this.needsTokenTypeIds = openedSession.getInputNames().contains(TOKEN_TYPE_IDS);
            HuggingFaceTokenizer.Builder builder = HuggingFaceTokenizer.builder()
                    .optTokenizerPath(tokenizerFile)
                    .optTruncation(true)
                    .optMaxLength(maxTokens)
                    .optPadding(true);
            builder = truncate == Truncate.FIRST ? builder.optTruncateFirstOnly() : builder.optTruncateSecondOnly();
            openedTokenizer = builder.build();
            this.tokenizer = openedTokenizer;
            openedCounter = HuggingFaceTokenizer.builder()
                    .optTokenizerPath(tokenizerFile)
                    .optTruncation(false)
                    .optPadding(false)
                    .build();
            this.counter = openedCounter;
            run(List.of(PROBE), List.of(PROBE));
        } catch (OrtException e) {
            closeQuietly(openedSession, openedTokenizer, openedCounter);
            throw new IllegalStateException("Falha ao abrir o modelo " + name, e);
        } catch (IOException e) {
            closeQuietly(openedSession, openedTokenizer, openedCounter);
            throw new UncheckedIOException("Falha ao abrir o tokenizer do modelo " + name, e);
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

    /**
     * Corta o texto do lado que o tokenizer não corta em no máximo metade do orçamento de tokens. Sem isso um texto
     * que sozinho enche o orçamento deixa o tokenizer do par sem espaço para o outro lado. Cortar por caracteres e
     * reconferir converge em poucas voltas, porque cada volta reduz na proporção do excesso.
     */
    public String limit(String text) {
        ensureOpen();
        String cut = text;
        int tokens = countTokens(cut);
        while (tokens > sideBudget && cut.length() > 1) {
            int keep = Math.max(1, Math.min(cut.length() - 1, (int) ((long) cut.length() * sideBudget / tokens)));
            if (Character.isHighSurrogate(cut.charAt(keep - 1)) && keep > 1) {
                keep--;
            }
            cut = cut.substring(0, keep);
            tokens = countTokens(cut);
        }
        return cut;
    }

    /** Lança {@link IllegalStateException} se o modelo já foi fechado. */
    public void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("O modelo ONNX já foi fechado");
        }
    }

    private int countTokens(String text) {
        return counter.encode(text, false, false).getIds().length;
    }

    /**
     * Roda o modelo sobre os pares {@code (firsts[i], seconds[i])} num único lote, preenchido até o maior par. Devolve
     * uma linha de logits por par.
     */
    public float[][] run(List<String> firsts, List<String> seconds) throws OrtException {
        ensureOpen();
        Encoding[] encodings = tokenizer.batchEncode(new PairList<>(firsts, seconds));
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
                return (float[][]) result.get(0).getValue();
            }
        } finally {
            inputs.values().forEach(OnnxTensor::close);
        }
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
            counter.close();
        }
    }
}
