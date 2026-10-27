package io.github.rennaraujo.sage.question;

import io.github.rennaraujo.sage.document.Source;
import io.github.rennaraujo.sage.search.RetrievalProperties;
import io.github.rennaraujo.sage.search.ScoredChunk;
import io.github.rennaraujo.sage.search.SearchIndex;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;

/**
 * Decide o caminho da pergunta depois da busca. NOT_DOCUMENTED responde com texto fixo sem chamar modelo;
 * STRONG_MODEL (Sonnet) recebe as comparações e os fluxos, que pedem análise entre fontes;
 * FAST_MODEL (Haiku) fica com o resto e ainda pode escalar sozinho. O limiar vem de
 * {@code sage.retrieval.min-score} quando o reranker está ligado e de {@code min-score-without-reranker} quando não
 * está, porque sem reranker o score é cobertura lexical, outra escala.
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
     * consultar o índice duas vezes). Versão faltante só encerra a pergunta quando ela é apenas pública e não sobra
     * nada para responder: numa pergunta mista o lado interno ainda pode responder, e numa comparação entre versões as
     * que existem são respondidas.
     */
    public Route decide(QuestionAnalysis analysis, List<ScoredChunk> chunks, List<String> missing) {
        boolean somethingLeft =
                perVersion(analysis) && missing.size() < analysis.versions().size();
        if (publicOnly(analysis) && !missing.isEmpty() && !somethingLeft) {
            return Route.NOT_DOCUMENTED;
        }
        if (chunks.isEmpty() || topScore(chunks) < threshold) {
            return Route.NOT_DOCUMENTED;
        }
        return strong(analysis.type()) ? Route.STRONG_MODEL : Route.FAST_MODEL;
    }

    /** Comparação e fluxo pedem análise entre trechos e documentos; o modelo forte responde direto. */
    private static boolean strong(QuestionType type) {
        return type == QuestionType.COMPARISON || type == QuestionType.FLOW;
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

    /**
     * Comparação entre duas ou mais versões de uma linguagem pública: cada versão é buscada separadamente, para uma
     * não tomar o contexto da outra. Numa pergunta mista a documentação interna vira mais um grupo, com busca própria.
     */
    static boolean perVersion(QuestionAnalysis analysis) {
        return analysis.type() == QuestionType.COMPARISON
                && analysis.sources().contains(Source.PUBLIC)
                && analysis.language().isPresent()
                && analysis.versions().size() >= 2;
    }

    /** Trechos com score no limiar ativo ou acima; numa comparação por versão, o que sobra é o que o grupo tem. */
    public List<ScoredChunk> relevant(List<ScoredChunk> chunks) {
        return chunks.stream().filter(c -> c.score() >= threshold).toList();
    }

    static boolean publicOnly(QuestionAnalysis analysis) {
        return analysis.sources().equals(Set.of(Source.PUBLIC));
    }

    static double topScore(List<ScoredChunk> chunks) {
        return chunks.stream().mapToDouble(ScoredChunk::score).max().orElse(0.0);
    }
}
