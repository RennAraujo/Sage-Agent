package io.github.rennaraujo.sage.search;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rennaraujo.sage.document.Document;
import io.github.rennaraujo.sage.document.DocumentId;
import io.github.rennaraujo.sage.document.DocumentMetadata;
import io.github.rennaraujo.sage.document.Source;
import java.net.URI;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RetrieverTest {

    private static final int DOCS = 12;
    private static final int CONTEXT = 5;
    private static final int CANDIDATES = 8;
    private static final int COMPARISON_MAX = 8;
    /** O limiar de rota da Task 11 (sage.retrieval.min-score). */
    private static final double MIN_SCORE = 0.35;

    private final RetrievalProperties properties =
            new RetrievalProperties(1500, CANDIDATES, CONTEXT, MIN_SCORE, 0.0, COMPARISON_MAX);
    private LuceneSearchIndex index;

    @BeforeEach
    void setUp() {
        index = new LuceneSearchIndex(properties, new IndexReadiness());
    }

    @AfterEach
    void tearDown() {
        index.close();
    }

    @Test
    void returnsAtMostFiveSortedDesc() {
        fill(DOCS);
        FakeReranker reranker = new FakeReranker();
        Retriever retriever = new Retriever(index, Optional.of(reranker), properties);

        List<ScoredChunk> found = retriever.retrieve("ordenar lista java", SearchFilter.none());

        assertThat(found).hasSize(CONTEXT);
        assertThat(found).extracting(ScoredChunk::score).isSortedAccordingTo(Comparator.reverseOrder());
        assertThat(reranker.candidateCounts()).containsExactly(CANDIDATES);
        assertThat(retriever.rerankerActive()).isTrue();
    }

    @Test
    void explicitLimitOverridesContextChunks() {
        fill(DOCS);
        Retriever retriever = new Retriever(index, Optional.of(new FakeReranker()), properties);

        assertThat(retriever.retrieve("ordenar lista java", SearchFilter.none(), 2))
                .hasSize(2);
        assertThat(retriever.retrieve("ordenar lista java", SearchFilter.none(), 3))
                .hasSize(3);
        assertThat(retriever.contextChunks()).isEqualTo(CONTEXT);
        assertThat(retriever.comparisonMaxChunks()).isEqualTo(COMPARISON_MAX);
    }

    @Test
    void comparisonMaxChunksBelowFourIsRejected() {
        try (jakarta.validation.ValidatorFactory factory =
                jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            jakarta.validation.Validator validator = factory.getValidator();

            assertThat(validator.validate(new RetrievalProperties(1500, CANDIDATES, CONTEXT, MIN_SCORE, 0.0, 3)))
                    .extracting(v -> v.getPropertyPath().toString())
                    .containsExactly("comparisonMaxChunks");
            assertThat(validator.validate(new RetrievalProperties(1500, CANDIDATES, CONTEXT, MIN_SCORE, 0.0, 4)))
                    .isEmpty();
        }
    }

    @Test
    void rerankerReadsTheCleanedQuestionWhileTheSearchKeepsTheOriginal() {
        index.upsert(doc("publico/java/25/a.md", "# A\nsealed classes restringem subclasses permitidas.\n"));
        index.upsert(doc("publico/java/21/a.md", "# A\nsealed classes restringem subclasses permitidas.\n"));
        java.util.List<String> seen = new java.util.ArrayList<>();
        Retriever retriever = new Retriever(
                index,
                Optional.of((query, candidates) -> {
                    seen.add(query);
                    return new FakeReranker().rerank(query, candidates);
                }),
                properties,
                question -> question.replace(" java 21", "").replace(" java 25", ""));

        List<ScoredChunk> found = retriever.retrieve("sealed classes java 21 java 25", SearchFilter.none());

        assertThat(seen).containsExactly("sealed classes");
        assertThat(found).hasSize(2);
    }

    @Test
    void blankCleanedQuestionFallsBackToTheOriginal() {
        index.upsert(doc("publico/java/25/a.md", "# A\nsealed classes restringem subclasses.\n"));
        java.util.List<String> seen = new java.util.ArrayList<>();
        Retriever retriever = new Retriever(
                index,
                Optional.of((query, candidates) -> {
                    seen.add(query);
                    return new FakeReranker().rerank(query, candidates);
                }),
                properties,
                question -> "  ");

        retriever.retrieve("sealed classes", SearchFilter.none());

        assertThat(seen).containsExactly("sealed classes");
    }

    @Test
    void hybridTopIsTheFirstSearchHitWithTheRerankerScore() {
        index.upsert(doc("publico/java/21/a.md", "# A\nordenar ordenar ordenar lista lista lista.\n"));
        index.upsert(doc("publico/java/21/b.md", "# B\nordenar lista java stream collectors.\n"));
        Retriever retriever = new Retriever(index, Optional.of(new FakeReranker()), properties);
        String firstHit = index.search("ordenar lista java", SearchFilter.none(), 1)
                .get(0)
                .id();

        Optional<ScoredChunk> top = retriever.hybridTop("ordenar lista java", SearchFilter.none());

        assertThat(top).isPresent();
        assertThat(top.get().chunk().id()).isEqualTo(firstHit);
        assertThat(top.get().score()).isBetween(0.0, 1.0);
        assertThat(retriever.hybridTop("zzzz yyyy", SearchFilter.none())).isEmpty();
    }

    @Test
    void searchDepthDefaultsTo30AndCandidatesBoundTheRerankedPairs() {
        assertThat(new RetrievalProperties(1500, 12, 5, 0.2, 0.0, 8).searchDepth())
                .isEqualTo(30);
        FakeReranker reranker = new FakeReranker();
        RetrievalProperties deep = new RetrievalProperties(1500, 3, CONTEXT, MIN_SCORE, 0.0, COMPARISON_MAX, 30);
        LuceneSearchIndex deepIndex = new LuceneSearchIndex(deep, new IndexReadiness());
        try {
            for (int i = 0; i < DOCS; i++) {
                deepIndex.upsert(doc("publico/java/25/d" + i + ".md", "# D" + i + "\nordenar lista java " + i + ".\n"));
            }
            new Retriever(deepIndex, Optional.of(reranker), deep).retrieve("ordenar lista java", SearchFilter.none());

            assertThat(reranker.candidateCounts()).containsExactly(3);
        } finally {
            deepIndex.close();
        }
    }

    @Test
    void emptyIndexReturnsEmpty() {
        FakeReranker reranker = new FakeReranker();
        Retriever retriever = new Retriever(index, Optional.of(reranker), properties);

        assertThat(retriever.retrieve("ordenar lista", SearchFilter.none())).isEmpty();
        assertThat(reranker.candidateCounts()).isEmpty();
    }

    @Test
    void rerankerCanPromoteALowerRankedCandidate() {
        index.upsert(doc("publico/java/25/a.md", "# A\nordenar ordenar ordenar lista lista lista.\n"));
        index.upsert(doc("publico/java/25/b.md", "# B\nordenar lista java stream collectors.\n"));
        Retriever retriever = new Retriever(index, Optional.of(new FakeReranker()), properties);

        List<ScoredChunk> found = retriever.retrieve("ordenar lista java", SearchFilter.none());

        assertThat(found.get(0).chunk().text()).contains("stream collectors");
    }

    @Test
    void withoutRerankerKeepsIndexOrderAndFlagsIt() {
        fill(DOCS);
        Retriever retriever = new Retriever(index, Optional.empty(), properties);

        List<ScoredChunk> found = retriever.retrieve("ordenar lista java", SearchFilter.none());

        assertThat(retriever.rerankerActive()).isFalse();
        assertThat(found).hasSize(CONTEXT);
        assertThat(found).extracting(ScoredChunk::score).isSortedAccordingTo(Comparator.reverseOrder());
        List<String> indexOrder = index.search("ordenar lista java", SearchFilter.none(), CANDIDATES).stream()
                .limit(CONTEXT)
                .map(Chunk::id)
                .toList();
        assertThat(found.stream().map(s -> s.chunk().id()).toList()).isEqualTo(indexOrder);
    }

    @Test
    void fallbackScoreSeparatesCoveredFromUncoveredQuestions() {
        index.upsert(doc("publico/java/25/sort.md", "# Sorting\nComo ordenar uma lista com Comparator.\n"));
        Retriever retriever = new Retriever(index, Optional.empty(), properties);

        double covered = retriever
                .retrieve("ordenar lista comparator", SearchFilter.none())
                .get(0)
                .score();
        List<ScoredChunk> partial = retriever.retrieve("ordenar certificado kubernetes", SearchFilter.none());

        assertThat(covered).isGreaterThan(MIN_SCORE);
        assertThat(partial).allSatisfy(s -> assertThat(s.score()).isLessThan(MIN_SCORE));
    }

    @Test
    void fallbackScoresStayWithinZeroOne() {
        fill(DOCS);
        Retriever retriever = new Retriever(index, Optional.empty(), properties);

        assertThat(retriever.retrieve("ordenar lista java", SearchFilter.none()))
                .allSatisfy(s -> assertThat(s.score()).isBetween(0.0, 1.0));
    }

    private void fill(int count) {
        for (int i = 0; i < count; i++) {
            index.upsert(doc(
                    "publico/java/25/doc" + i + ".md",
                    "# Doc " + i + "\nComo ordenar uma lista java " + i + " com Comparator.\n"));
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
}
