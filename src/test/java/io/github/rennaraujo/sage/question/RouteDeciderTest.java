package io.github.rennaraujo.sage.question;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.rennaraujo.sage.document.DocumentId;
import io.github.rennaraujo.sage.document.Source;
import io.github.rennaraujo.sage.search.Chunk;
import io.github.rennaraujo.sage.search.RetrievalProperties;
import io.github.rennaraujo.sage.search.ScoredChunk;
import io.github.rennaraujo.sage.search.SearchIndex;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RouteDeciderTest {

    private static final double MIN_SCORE = 0.35;
    private static final double MIN_SCORE_WITHOUT_RERANKER = 0.1;
    private static final RetrievalProperties PROPERTIES =
            new RetrievalProperties(1500, 20, 5, MIN_SCORE, MIN_SCORE_WITHOUT_RERANKER, 8);

    private final SearchIndex index = mock(SearchIndex.class);

    @BeforeEach
    void setUp() {
        when(index.versionsOf("java")).thenReturn(new TreeSet<>(List.of("17", "21")));
    }

    @Test
    void belowMinScoreIsNotDocumented() {
        RouteDecider decider = new RouteDecider(index, PROPERTIES, true);

        assertThat(decider.decide(java("21"), List.of(scored(0.30), scored(0.1))))
                .isEqualTo(Route.NOT_DOCUMENTED);
    }

    @Test
    void aboveMinScoreIsFastModel() {
        RouteDecider decider = new RouteDecider(index, PROPERTIES, true);

        assertThat(decider.decide(java("21"), List.of(scored(0.80), scored(0.2))))
                .isEqualTo(Route.FAST_MODEL);
    }

    @Test
    void noChunksIsNotDocumented() {
        RouteDecider decider = new RouteDecider(index, PROPERTIES, true);

        assertThat(decider.decide(java("21"), List.of())).isEqualTo(Route.NOT_DOCUMENTED);
    }

    @Test
    void withoutRerankerUsesItsOwnThreshold() {
        RouteDecider decider = new RouteDecider(index, PROPERTIES, false);

        assertThat(decider.decide(java("21"), List.of(scored(0.2)))).isEqualTo(Route.FAST_MODEL);
        assertThat(decider.decide(java("21"), List.of(scored(0.05)))).isEqualTo(Route.NOT_DOCUMENTED);
    }

    @Test
    void publicVersionMissingFromAcervoIsNotDocumented() {
        RouteDecider decider = new RouteDecider(index, PROPERTIES, true);

        assertThat(decider.missingVersions(java("8"))).containsExactly("8");
        assertThat(decider.decide(java("8"), List.of(scored(0.9)))).isEqualTo(Route.NOT_DOCUMENTED);
    }

    @Test
    void mixedQuestionReportsMissingVersionButStillRoutes() {
        RouteDecider decider = new RouteDecider(index, PROPERTIES, true);
        QuestionAnalysis mixed = new QuestionAnalysis(
                Set.of(Source.PUBLIC, Source.INTERNAL),
                Optional.of("java"),
                List.of("8"),
                QuestionType.LOOKUP,
                true,
                true);

        assertThat(decider.missingVersions(mixed)).containsExactly("8");
        assertThat(decider.decide(mixed, List.of(scored(0.9)))).isEqualTo(Route.FAST_MODEL);
    }

    @Test
    void decideWithPrecomputedMissingDoesNotQueryIndexAgain() {
        RouteDecider decider = new RouteDecider(index, PROPERTIES, true);

        assertThat(decider.decide(java("8"), List.of(scored(0.9)), List.of("8")))
                .isEqualTo(Route.NOT_DOCUMENTED);
        assertThat(decider.decide(java("21"), List.of(scored(0.9)), List.of())).isEqualTo(Route.FAST_MODEL);
        org.mockito.Mockito.verify(index, org.mockito.Mockito.never()).versionsOf("java");
    }

    @Test
    void comparisonGoesToStrongModel() {
        RouteDecider decider = new RouteDecider(index, PROPERTIES, true);

        assertThat(decider.decide(comparison("17", "21"), List.of(scored(0.9)))).isEqualTo(Route.STRONG_MODEL);
        assertThat(decider.decide(comparison(), List.of(scored(0.9)))).isEqualTo(Route.STRONG_MODEL);
        assertThat(decider.decide(comparison("17", "21"), List.of(scored(0.1)))).isEqualTo(Route.NOT_DOCUMENTED);
    }

    @Test
    void comparisonWithSomeVersionsMissingStillRoutes() {
        RouteDecider decider = new RouteDecider(index, PROPERTIES, true);

        assertThat(decider.missingVersions(comparison("21", "8"))).containsExactly("8");
        assertThat(decider.decide(comparison("21", "8"), List.of(scored(0.9)))).isEqualTo(Route.STRONG_MODEL);
        assertThat(decider.decide(comparison("7", "8"), List.of(scored(0.9)))).isEqualTo(Route.NOT_DOCUMENTED);
        assertThat(RouteDecider.perVersion(comparison("21", "8"))).isTrue();
        assertThat(RouteDecider.perVersion(comparison("21"))).isFalse();
    }

    @Test
    void mixedComparisonIsAlsoPerVersion() {
        QuestionAnalysis mixed = new QuestionAnalysis(
                Set.of(Source.PUBLIC, Source.INTERNAL),
                Optional.of("java"),
                List.of("17", "21"),
                QuestionType.COMPARISON,
                true,
                true);

        assertThat(RouteDecider.perVersion(mixed)).isTrue();
    }

    @Test
    void relevantKeepsOnlyChunksAtOrAboveTheActiveThreshold() {
        RouteDecider withReranker = new RouteDecider(index, PROPERTIES, true);
        RouteDecider withoutReranker = new RouteDecider(index, PROPERTIES, false);

        assertThat(withReranker.relevant(List.of(scored(0.9), scored(0.35), scored(0.2))))
                .extracting(ScoredChunk::score)
                .containsExactly(0.9, 0.35);
        assertThat(withoutReranker.relevant(List.of(scored(0.2), scored(0.05))))
                .extracting(ScoredChunk::score)
                .containsExactly(0.2);
    }

    private static QuestionAnalysis comparison(String... versions) {
        return new QuestionAnalysis(
                Set.of(Source.PUBLIC), Optional.of("java"), List.of(versions), QuestionType.COMPARISON, false, true);
    }

    private static QuestionAnalysis java(String version) {
        return new QuestionAnalysis(
                Set.of(Source.PUBLIC), Optional.of("java"), List.of(version), QuestionType.LOOKUP, false, true);
    }

    private static ScoredChunk scored(double score) {
        Chunk chunk = new Chunk(
                "c" + score,
                DocumentId.of("publico/java/21/streams.md"),
                Source.PUBLIC,
                "java",
                Optional.of("21"),
                "Streams",
                "texto",
                0,
                true);
        return new ScoredChunk(chunk, score);
    }
}
