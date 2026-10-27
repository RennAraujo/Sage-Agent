package io.github.rennaraujo.sage.search.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rennaraujo.sage.document.Document;
import io.github.rennaraujo.sage.document.DocumentId;
import io.github.rennaraujo.sage.document.DocumentMetadata;
import io.github.rennaraujo.sage.document.Source;
import io.github.rennaraujo.sage.search.Chunk;
import io.github.rennaraujo.sage.search.IndexReadiness;
import io.github.rennaraujo.sage.search.LuceneSearchIndex;
import io.github.rennaraujo.sage.search.RetrievalProperties;
import io.github.rennaraujo.sage.search.SearchFilter;
import io.github.rennaraujo.sage.testsupport.ScratchDirs;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/** Roda só se os arquivos do modelo existirem em {@code sage.models.dir} (padrão {@code models}). */
@Tag("models")
@EnabledIf("modelsPresent")
class OnnxEmbeddingModelTest {

    private static final int EXPECTED_DIMENSIONS = 384;
    private static final double NORM_TOLERANCE = 1e-3;
    private static final int LIMIT = 5;
    private static final int THREADS = 4;
    private static final int TASKS = 16;
    private static final int HUGE_WORDS = 5000;

    private static OnnxEmbeddingModel model;

    static boolean modelsPresent() {
        return filesFromProperty().available();
    }

    private static ModelFiles filesFromProperty() {
        return ModelFiles.inDir(Path.of(System.getProperty("sage.models.dir", "models")));
    }

    @BeforeAll
    static void load() {
        model = new OnnxEmbeddingModel(filesFromProperty());
    }

    @AfterAll
    static void unload() {
        if (model != null) {
            model.close();
        }
    }

    @Test
    void hasDimensions384() {
        assertThat(model.dimensions()).isEqualTo(EXPECTED_DIMENSIONS);
        assertThat(model.embedPassage("qualquer texto")).hasSize(EXPECTED_DIMENSIONS);
    }

    @Test
    void vectorsAreNormalized() {
        Offset<Double> tolerance = Offset.offset(NORM_TOLERANCE);

        assertThat(norm(model.embedQuery("como ordenar uma lista"))).isCloseTo(1.0, tolerance);
        assertThat(norm(model.embedPassage("How to sort a List in Java"))).isCloseTo(1.0, tolerance);
    }

    @Test
    void crossLingualSimilarity() {
        float[] pt = model.embedQuery("como ordenar uma lista em java");
        float[] en = model.embedPassage("How to sort a List in Java");
        float[] unrelated = model.embedPassage("Configure TLS in Spring Boot");

        assertThat(cosine(pt, en)).isGreaterThan(cosine(pt, unrelated));
    }

    @Test
    void veryLongInputIsTruncatedInsteadOfFailing() {
        String huge = "palavra ".repeat(HUGE_WORDS);

        assertThat(model.embedPassage(huge)).hasSize(EXPECTED_DIMENSIONS);
    }

    @Test
    void safeUnderConcurrentUse() throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
            List<Future<float[]>> futures = new ArrayList<>();
            for (int i = 0; i < TASKS; i++) {
                String text = "texto numero " + i;
                futures.add(pool.submit(() -> model.embedPassage(text)));
            }
            for (Future<float[]> f : futures) {
                assertThat(f.get()).hasSize(EXPECTED_DIMENSIONS);
            }
        }
    }

    @Test
    void corruptTokenizerMakesTheConstructorThrowAfterTheSessionWasOpened() throws Exception {
        Path dir = ScratchDirs.create("sage-model");
        try {
            Files.writeString(dir.resolve("tokenizer.json"), "isto não é um tokenizer");
            ModelFiles files = new ModelFiles(filesFromProperty().model(), dir.resolve("tokenizer.json"));

            assertThatThrownBy(() -> new OnnxEmbeddingModel(files)).isInstanceOf(RuntimeException.class);
        } finally {
            ScratchDirs.delete(dir);
        }
    }

    @Test
    void embeddingAfterCloseFailsCleanly() {
        OnnxEmbeddingModel closed = new OnnxEmbeddingModel(filesFromProperty());
        closed.close();

        assertThatThrownBy(() -> closed.embedQuery("qualquer coisa")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> closed.embedPassage("qualquer coisa")).isInstanceOf(IllegalStateException.class);
        closed.close();
    }

    @Test
    void indexFindsPortugueseQueryInEnglishChunkWithRealModel() {
        try (LuceneSearchIndex index = new LuceneSearchIndex(
                new RetrievalProperties(1500, 20, 5, 0.35, 0.0), new IndexReadiness(), Optional.of(model))) {
            index.upsert(doc("publico/java/25/sort.md", "# Sorting\nHow to sort a List in Java with Comparator.\n"));
            index.upsert(doc("publico/java/25/tls.md", "# TLS\nConfigure TLS in Spring Boot with an SSL bundle.\n"));

            List<Chunk> found = index.search("como ordenar uma lista", SearchFilter.none(), LIMIT);

            assertThat(found).isNotEmpty();
            assertThat(found.get(0).text()).contains("sort a List");
        }
    }

    private static Document doc(String id, String body) {
        DocumentId documentId = DocumentId.of(id);
        DocumentMetadata meta = new DocumentMetadata(
                documentId,
                Source.PUBLIC,
                "java",
                Optional.of("25"),
                "geral",
                List.of(),
                Optional.of(URI.create("https://docs.example.org/")),
                LocalDate.of(2026, 10, 27),
                true);
        return new Document(meta, body);
    }

    private static double norm(float[] v) {
        double sum = 0;
        for (float x : v) {
            sum += x * x;
        }
        return Math.sqrt(sum);
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
        }
        return dot / (norm(a) * norm(b));
    }
}
