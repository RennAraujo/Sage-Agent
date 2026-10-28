package io.github.rennaraujo.sage.search.rerank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rennaraujo.sage.document.DocumentId;
import io.github.rennaraujo.sage.document.Source;
import io.github.rennaraujo.sage.search.Chunk;
import io.github.rennaraujo.sage.search.ScoredChunk;
import io.github.rennaraujo.sage.search.embedding.ModelFiles;
import io.github.rennaraujo.sage.testsupport.LatencyBudget;
import io.github.rennaraujo.sage.testsupport.ProductionSettings;
import io.github.rennaraujo.sage.testsupport.ScratchDirs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Roda só se os arquivos do reranker existirem em {@code sage.models.dir} (padrão {@code models}). */
@Tag("models")
@EnabledIf("modelsPresent")
class OnnxRerankerTest {

    private static final Logger LOG = LoggerFactory.getLogger(OnnxRerankerTest.class);
    private static final RerankProperties PROPERTIES =
            new RerankProperties(ProductionSettings.integer("rerank", "max-tokens"));
    private static final int PAIRS = 30;
    /** Os candidatos de produção ({@code sage.retrieval.candidates}). */
    private static final int CANDIDATES = ProductionSettings.integer("retrieval", "candidates");

    private static final int CEILING_REPEATS = 46;
    private static final long MAX_MILLIS = 500;
    private static final int HUGE_WORDS = 5000;
    private static final int TYPICAL_REPEATS = 8;
    /**
     * Guarda contra regressão com a configuração de produção (candidatos e tokens do {@code application.yml}); o
     * valor medido está em docs/decisoes.md. O orçamento estrito só vale com {@code -Peval}.
     */
    private static final long CEILING_BUDGET_MILLIS = 1500;

    private static OnnxReranker reranker;

    static boolean modelsPresent() {
        return filesFromProperty().available();
    }

    private static ModelFiles filesFromProperty() {
        return ModelFiles.rerankerInDir(Path.of(System.getProperty("sage.models.dir", "models")));
    }

    @BeforeAll
    static void load() {
        reranker = new OnnxReranker(filesFromProperty(), PROPERTIES);
    }

    @AfterAll
    static void unload() {
        if (reranker != null) {
            reranker.close();
        }
    }

    @Test
    void relevantPairScoresHigher() {
        Chunk relevant = chunk("a", "Sorting", "Para ordenar uma lista em Java use List.sort com um Comparator.");
        Chunk unrelated = chunk("b", "TLS", "Configure TLS no Spring Boot com um SSL bundle e um keystore PKCS12.");

        List<ScoredChunk> ranked = reranker.rerank("como ordenar uma lista em java", List.of(unrelated, relevant));

        assertThat(ranked.get(0).chunk().id()).isEqualTo("a");
        assertThat(ranked.get(0).score()).isGreaterThan(ranked.get(1).score());
    }

    @Test
    void relevantEnglishChunkForPortugueseQuestionBeatsUnrelatedOne() {
        Chunk relevant = chunk("a", "Sorting", "How to sort a List in Java: call List.sort with a Comparator.");
        Chunk unrelated = chunk("b", "TLS", "Configure TLS in Spring Boot with an SSL bundle and a PKCS12 keystore.");

        List<ScoredChunk> ranked = reranker.rerank("como ordenar uma lista em java", List.of(unrelated, relevant));

        assertThat(ranked.get(0).chunk().id()).isEqualTo("a");
    }

    @Test
    void scoresWithinZeroOne() {
        List<ScoredChunk> ranked = reranker.rerank(
                "como ordenar uma lista em java",
                List.of(chunk("a", "x", "ordenar lista"), chunk("b", "y", "nada a ver com isso")));

        assertThat(ranked).allSatisfy(s -> assertThat(s.score()).isBetween(0.0, 1.0));
    }

    @Test
    void emptyCandidatesGiveEmptyResult() {
        assertThat(reranker.rerank("qualquer coisa", List.of())).isEmpty();
    }

    @Test
    void questionOf2000CharsStillScores() {
        String question = "como configurar o certificado TLS no servidor de aplicacao "
                .repeat(34)
                .substring(0, 2000);

        List<ScoredChunk> ranked =
                reranker.rerank(question, List.of(chunk("a", "TLS", "Configure TLS no Spring Boot.")));

        assertThat(ranked).singleElement().satisfies(s -> assertThat(s.score()).isBetween(0.0, 1.0));
    }

    @Test
    void questionOf2000WordsStillScores() {
        String question = "palavra ".repeat(HUGE_WORDS / 2);

        List<ScoredChunk> ranked =
                reranker.rerank(question, List.of(chunk("a", "TLS", "Configure TLS no Spring Boot.")));

        assertThat(ranked).singleElement().satisfies(s -> assertThat(s.score()).isBetween(0.0, 1.0));
    }

    @Test
    void veryLongChunkIsTruncatedInsteadOfFailing() {
        Chunk huge = chunk("h", "Enorme", "palavra ".repeat(HUGE_WORDS));

        assertThat(reranker.rerank("palavra", List.of(huge))).hasSize(1);
    }

    @Test
    void thirtyShortPairsUnder500ms() {
        List<Chunk> candidates = chunksOfRepeats(PAIRS, TYPICAL_REPEATS);
        reranker.rerank("como configurar o TLS", candidates);

        long start = System.nanoTime();
        List<ScoredChunk> ranked = reranker.rerank("como configurar o TLS", candidates);
        long millis = (System.nanoTime() - start) / 1_000_000;

        LOG.info("reranker: {} pares de trecho curto (~260 caracteres) em {} ms", PAIRS, millis);
        assertThat(ranked).hasSize(PAIRS);
        assertThat(millis).isLessThan(LatencyBudget.millis(MAX_MILLIS));
    }

    /** O caso que decide o orçamento: os candidatos de produção, cada trecho no teto de 1500 caracteres. */
    @Test
    void productionCandidatesAtTheCeilingWithinBudget() {
        List<Chunk> candidates = chunksOfRepeats(CANDIDATES, CEILING_REPEATS);
        reranker.rerank("como configurar o TLS", candidates);

        long start = System.nanoTime();
        List<ScoredChunk> ranked = reranker.rerank("como configurar o TLS", candidates);
        long millis = (System.nanoTime() - start) / 1_000_000;

        LOG.info(
                "reranker: {} pares de trecho no teto (~1500 caracteres, max-tokens {}) em {} ms",
                CANDIDATES,
                PROPERTIES.maxTokens(),
                millis);
        assertThat(ranked).hasSize(CANDIDATES);
        assertThat(millis).isLessThan(LatencyBudget.millis(CEILING_BUDGET_MILLIS));
    }

    private static List<Chunk> chunksOfRepeats(int count, int repeats) {
        List<Chunk> candidates = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            candidates.add(chunk("c" + i, "Seção " + i, ("Texto de documentação número " + i + ". ").repeat(repeats)));
        }
        return candidates;
    }

    @Test
    void corruptTokenizerMakesTheConstructorThrowAfterTheSessionWasOpened() throws Exception {
        Path dir = ScratchDirs.create("sage-reranker");
        try {
            Files.writeString(dir.resolve("tokenizer.json"), "isto não é um tokenizer");
            ModelFiles files = new ModelFiles(filesFromProperty().model(), dir.resolve("tokenizer.json"));

            assertThatThrownBy(() -> new OnnxReranker(files, PROPERTIES)).isInstanceOf(RuntimeException.class);
        } finally {
            ScratchDirs.delete(dir);
        }
    }

    @Test
    void rerankingAfterCloseFailsCleanly() {
        OnnxReranker closed = new OnnxReranker(filesFromProperty(), PROPERTIES);
        closed.close();

        List<Chunk> one = List.of(chunk("a", "x", "texto"));
        assertThatThrownBy(() -> closed.rerank("pergunta", one)).isInstanceOf(IllegalStateException.class);
        closed.close();
    }

    private static Chunk chunk(String id, String heading, String text) {
        return new Chunk(
                id,
                DocumentId.of("publico/java/25/" + id + ".md"),
                Source.PUBLIC,
                "java",
                Optional.of("25"),
                heading,
                text,
                0,
                true);
    }
}
