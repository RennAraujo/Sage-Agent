package io.github.rennaraujo.sage.search;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reciprocal Rank Fusion: cada lista dá a cada id a nota {@code 1 / (k + posição)} (posição começa em 1) e as notas
 * se somam. Só a posição conta, então não é preciso normalizar o score do BM25 contra a similaridade do cosseno, que
 * vivem em escalas diferentes. Quem aparece nas duas listas sobe; empate sai na ordem em que o id apareceu primeiro
 * (BM25 antes do vetor).
 */
public final class HybridRanker {

    /** Constante do RRF; 60 é o valor do artigo original e raramente precisa mudar. */
    static final int RRF_K = 60;

    public List<String> fuse(List<String> bm25Ids, List<String> vectorIds, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        Map<String, Double> scores = new LinkedHashMap<>();
        accumulate(scores, bm25Ids);
        accumulate(scores, vectorIds);
        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(limit)
                .map(Map.Entry::getKey)
                .toList();
    }

    private static void accumulate(Map<String, Double> scores, List<String> ranked) {
        Set<String> seen = new HashSet<>();
        int rank = 0;
        for (String id : ranked) {
            if (seen.add(id)) {
                rank++;
                scores.merge(id, 1.0 / (RRF_K + rank), Double::sum);
            }
        }
    }
}
