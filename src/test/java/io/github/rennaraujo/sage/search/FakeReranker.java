package io.github.rennaraujo.sage.search;

import io.github.rennaraujo.sage.search.rerank.Reranker;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Reranker de mentira: a nota é a fração das palavras da pergunta presentes no trecho. Guarda o que recebeu. */
public final class FakeReranker implements Reranker {

    private final List<Integer> candidateCounts = new ArrayList<>();

    @Override
    public List<ScoredChunk> rerank(String query, List<Chunk> candidates) {
        candidateCounts.add(candidates.size());
        Set<String> wanted =
                new HashSet<>(List.of(query.toLowerCase(Locale.ROOT).split("\\W+")));
        return candidates.stream()
                .map(c -> new ScoredChunk(c, overlap(wanted, c.text().toLowerCase(Locale.ROOT))))
                .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed())
                .toList();
    }

    public List<Integer> candidateCounts() {
        return candidateCounts;
    }

    private static double overlap(Set<String> wanted, String text) {
        long hits = wanted.stream().filter(text::contains).count();
        return (double) hits / wanted.size();
    }
}
