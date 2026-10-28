package io.github.rennaraujo.sage.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.github.rennaraujo.sage.document.DocumentId;
import io.github.rennaraujo.sage.document.Source;
import io.github.rennaraujo.sage.question.guard.OnnxNliChecker;
import io.github.rennaraujo.sage.search.Chunk;
import io.github.rennaraujo.sage.search.ScoredChunk;
import io.github.rennaraujo.sage.search.embedding.ModelFiles;
import io.github.rennaraujo.sage.search.embedding.OnnxEmbeddingModel;
import io.github.rennaraujo.sage.search.rerank.OnnxReranker;
import io.github.rennaraujo.sage.search.rerank.RerankProperties;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * Entradas que quebram tokenizers: pergunta vazia, só caracteres de controle ou de largura zero,
 * trecho vazio e 3000 caracteres sem espaço. Nenhuma pode lançar exceção nem derrubar a JVM (o tokenizer é código
 * nativo em Rust: um pânico lá derrubaria o processo, não só a requisição). Segurança contra negação de serviço, por
 * isso roda no {@code verify} padrão quando os modelos estão no disco.
 */
@Tag("models")
@EnabledIf("modelsPresent")
class TokenizerEdgeCasesTest {

    private static final int LONG_RUN = 3000;
    private static final Map<String, String> TEXTS = Map.of(
            "vazio", "",
            "só espaço", "   \n\t  ",
            "controle", "\u0000\u0001\u0002\u0007\u001b",
            "largura zero", codePoints(0x200B, 0x200B, 0x200D, 0xFEFF, 0x2060),
            "3000 sem espaço", "a".repeat(LONG_RUN),
            "3000 emojis", codePoints(0x1F600).repeat(LONG_RUN),
            "surrogate solto", "abc\ud83dxyz");

    /** Caracteres invisíveis ou fora do plano básico escritos por código, para o fonte não carregá-los. */
    private static String codePoints(int... points) {
        return new String(points, 0, points.length);
    }

    private static OnnxReranker reranker;
    private static OnnxEmbeddingModel embeddings;

    static boolean modelsPresent() {
        return EvalModels.embeddingsAndReranker();
    }

    private static Path dir() {
        return Path.of(System.getProperty("sage.models.dir", "models"));
    }

    @BeforeAll
    static void load() {
        reranker = new OnnxReranker(ModelFiles.rerankerInDir(dir()), new RerankProperties(256));
        embeddings = new OnnxEmbeddingModel(ModelFiles.inDir(dir()));
    }

    @AfterAll
    static void unload() {
        if (reranker != null) {
            reranker.close();
        }
        if (embeddings != null) {
            embeddings.close();
        }
    }

    @Test
    void rerankerNeverThrowsOnOddQuestionsOrChunks() {
        for (Map.Entry<String, String> question : TEXTS.entrySet()) {
            for (Map.Entry<String, String> chunkText : TEXTS.entrySet()) {
                Chunk chunk = chunk(chunkText.getValue(), chunkText.getValue());
                assertThatCode(() -> {
                            List<ScoredChunk> scored = reranker.rerank(question.getValue(), List.of(chunk));
                            assertThat(scored).hasSize(1);
                            assertThat(scored.get(0).score()).isBetween(0.0, 1.0);
                        })
                        .as("reranker com pergunta '%s' e trecho '%s'", question.getKey(), chunkText.getKey())
                        .doesNotThrowAnyException();
            }
        }
    }

    @Test
    void embeddingsNeverThrowOnOddText() {
        for (Map.Entry<String, String> text : TEXTS.entrySet()) {
            assertThatCode(() -> {
                        assertThat(embeddings.embedQuery(text.getValue())).hasSize(embeddings.dimensions());
                        assertThat(embeddings.embedPassage(text.getValue())).hasSize(embeddings.dimensions());
                    })
                    .as("embedding de '%s'", text.getKey())
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void nliNeverThrowsOnOddSentencesOrEvidence() {
        EvalStack stack = EvalStack.get();
        EvalModels.requireNli();
        OnnxNliChecker nli = stack.nli();
        for (Map.Entry<String, String> sentence : TEXTS.entrySet()) {
            for (Map.Entry<String, String> evidence : TEXTS.entrySet()) {
                assertThatCode(() -> nli.isSupported(sentence.getValue(), List.of(evidence.getValue())))
                        .as("NLI com frase '%s' e trecho '%s'", sentence.getKey(), evidence.getKey())
                        .doesNotThrowAnyException();
            }
        }
    }

    private static Chunk chunk(String heading, String text) {
        return new Chunk(
                "c-edge",
                DocumentId.of("publico/java/21/borda.md"),
                Source.PUBLIC,
                "java",
                Optional.of("21"),
                heading,
                text,
                0,
                true);
    }
}
