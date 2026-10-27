package io.github.rennaraujo.sage.search;

import io.github.rennaraujo.sage.search.rerank.Reranker;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Busca híbrida seguida de reranking: pede {@code candidates} trechos ao índice, o reranker pontua cada par e sobram
 * os {@code contextChunks} melhores, do maior score para o menor.
 *
 * <p>Sem reranker (arquivos do modelo ausentes) a ordem é a da busca híbrida e o score deixa de ser uma probabilidade
 * de relevância: vira a cobertura lexical (fração dos termos da pergunta que aparecem no trecho), limitada pelo score
 * do trecho anterior para a lista continuar em ordem decrescente. É um substituto grosseiro, mas mantém o limiar
 * {@code sage.retrieval.min-score} com algum poder de separação em vez de aprovar ou reprovar toda pergunta.
 * {@link #rerankerActive()} diz qual dos dois scores está em uso.
 */
public final class Retriever {

    /** Termos menores que isso (artigos, preposições curtas) não contam na cobertura. */
    private static final int MIN_TERM_LENGTH = 3;

    private static final Set<String> STOPWORDS = Set.of(
            "como", "para", "com", "uma", "uns", "umas", "que", "por", "dos", "das", "nao", "sim", "qual", "quais",
            "onde", "quando", "the", "and", "for", "how", "what", "which", "where", "when", "with", "does", "can",
            "are", "you", "your", "this", "that", "from", "into");

    private final SearchIndex index;
    private final Optional<Reranker> reranker;
    private final RetrievalProperties properties;

    public Retriever(SearchIndex index, Optional<Reranker> reranker, RetrievalProperties properties) {
        this.index = index;
        this.reranker = reranker;
        this.properties = properties;
    }

    /** {@code true} quando o score vem do reranker; {@code false} quando é a cobertura lexical do fallback. */
    public boolean rerankerActive() {
        return reranker.isPresent();
    }

    /** Quantos trechos uma busca devolve por padrão ({@code sage.retrieval.context-chunks}). */
    public int contextChunks() {
        return properties.contextChunks();
    }

    /** Teto de trechos somados numa comparação com busca por versão ({@code sage.retrieval.comparison-max-chunks}). */
    public int comparisonMaxChunks() {
        return properties.comparisonMaxChunks();
    }

    public List<ScoredChunk> retrieve(String query, SearchFilter filter) {
        return retrieve(query, filter, properties.contextChunks());
    }

    /**
     * Como {@link #retrieve(String, SearchFilter)}, devolvendo até {@code limit} trechos. A comparação entre versões
     * usa isto para dar a cada versão a sua parte do contexto.
     */
    public List<ScoredChunk> retrieve(String query, SearchFilter filter, int limit) {
        List<Chunk> candidates = index.search(query, filter, properties.candidates());
        if (candidates.isEmpty()) {
            return List.of();
        }
        if (reranker.isPresent()) {
            return reranker.get().rerank(query, candidates).stream()
                    .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed())
                    .limit(limit)
                    .toList();
        }
        return fallback(query, candidates.stream().limit(limit).toList());
    }

    private static List<ScoredChunk> fallback(String query, List<Chunk> ordered) {
        Set<String> terms = termsOf(query);
        List<ScoredChunk> scored = new ArrayList<>(ordered.size());
        double ceiling = 1.0;
        for (Chunk chunk : ordered) {
            ceiling = Math.min(ceiling, coverage(terms, chunk));
            scored.add(new ScoredChunk(chunk, ceiling));
        }
        return List.copyOf(scored);
    }

    private static double coverage(Set<String> terms, Chunk chunk) {
        if (terms.isEmpty()) {
            return 0.0;
        }
        Set<String> present = new HashSet<>(termsOf(chunk.headingPath() + " " + chunk.text(), false));
        long hits = terms.stream().filter(present::contains).count();
        return (double) hits / terms.size();
    }

    private static Set<String> termsOf(String text) {
        return termsOf(text, true);
    }

    private static Set<String> termsOf(String text, boolean dropStopwords) {
        String folded = Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        Set<String> terms = new LinkedHashSet<>();
        for (String word : folded.split("[^a-z0-9]+")) {
            if (word.length() >= MIN_TERM_LENGTH && !(dropStopwords && STOPWORDS.contains(word))) {
                terms.add(word);
            }
        }
        return terms;
    }
}
