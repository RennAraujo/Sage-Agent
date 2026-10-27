package io.github.rennaraujo.sage.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class HybridRankerTest {

    private static final int LIMIT = 10;

    private final HybridRanker ranker = new HybridRanker();

    @Test
    void fusePutsDocInBothListsFirst() {
        List<String> fused = ranker.fuse(List.of("a", "b", "c"), List.of("c", "d"), LIMIT);

        assertThat(fused).first().isEqualTo("c");
        assertThat(fused).containsExactlyInAnyOrder("a", "b", "c", "d");
    }

    @Test
    void fuseRespectsLimit() {
        List<String> fused = ranker.fuse(List.of("a", "b", "c"), List.of("c", "d"), 2);

        assertThat(fused).hasSize(2).first().isEqualTo("c");
    }

    @Test
    void fuseWithOneEmptyListKeepsOrderOfTheOther() {
        assertThat(ranker.fuse(List.of("a", "b"), List.of(), LIMIT)).containsExactly("a", "b");
        assertThat(ranker.fuse(List.of(), List.of("x", "y"), LIMIT)).containsExactly("x", "y");
    }

    @Test
    void fuseIgnoresRepeatedIdsWithinAList() {
        assertThat(ranker.fuse(List.of("a", "a", "b"), List.of("b"), LIMIT)).containsExactly("b", "a");
    }

    @Test
    void fuseWithNonPositiveLimitIsEmpty() {
        assertThat(ranker.fuse(List.of("a"), List.of("a"), 0)).isEmpty();
    }
}
