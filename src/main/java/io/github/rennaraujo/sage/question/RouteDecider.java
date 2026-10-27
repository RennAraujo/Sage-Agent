package io.github.rennaraujo.sage.question;

import io.github.rennaraujo.sage.document.Source;
import io.github.rennaraujo.sage.search.RetrievalProperties;
import io.github.rennaraujo.sage.search.ScoredChunk;
import io.github.rennaraujo.sage.search.SearchIndex;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;

/**
 * Decide o caminho da pergunta depois da busca. Duas saídas por enquanto: NOT_DOCUMENTED, que responde com texto
 * fixo sem chamar modelo, e FAST_MODEL. O limiar vem de {@code sage.retrieval.min-score} quando o reranker está ligado
 * e de {@code min-score-without-reranker} quando não está, porque sem reranker o score é cobertura lexical, outra
 * escala.
 */
public final class RouteDecider {

    private final SearchIndex index;
    private final double threshold;

    public RouteDecider(SearchIndex index, RetrievalProperties properties, boolean rerankerActive) {
        this.index = index;
        this.threshold = rerankerActive ? properties.minScore() : properties.minScoreWithoutReranker();
    }

    public Route decide(QuestionAnalysis analysis, List<ScoredChunk> chunks) {
        return decide(analysis, chunks, missingVersions(analysis));
    }

    /**
     * Como {@link #decide(QuestionAnalysis, List)}, com as versões faltantes já calculadas por quem chamou (evita
     * consultar o índice duas vezes). Versão faltante só encerra a pergunta quando ela é apenas pública: numa pergunta
     * mista o lado interno ainda pode responder.
     */
    public Route decide(QuestionAnalysis analysis, List<ScoredChunk> chunks, List<String> missing) {
        if (publicOnly(analysis) && !missing.isEmpty()) {
            return Route.NOT_DOCUMENTED;
        }
        if (chunks.isEmpty() || topScore(chunks) < threshold) {
            return Route.NOT_DOCUMENTED;
        }
        return Route.FAST_MODEL;
    }

    /**
     * Versões públicas citadas na pergunta que não existem no acervo. Responder com outra versão sem avisar seria pior
     * que dizer que não tem.
     */
    public List<String> missingVersions(QuestionAnalysis analysis) {
        if (!analysis.sources().contains(Source.PUBLIC)
                || analysis.language().isEmpty()
                || analysis.versions().isEmpty()) {
            return List.of();
        }
        SortedSet<String> available = index.versionsOf(analysis.language().get());
        return analysis.versions().stream().filter(v -> !available.contains(v)).toList();
    }

    static boolean publicOnly(QuestionAnalysis analysis) {
        return analysis.sources().equals(Set.of(Source.PUBLIC));
    }

    static double topScore(List<ScoredChunk> chunks) {
        return chunks.stream().mapToDouble(ScoredChunk::score).max().orElse(0.0);
    }
}
