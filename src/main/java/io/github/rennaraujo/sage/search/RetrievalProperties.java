package io.github.rennaraujo.sage.search;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Parâmetros da recuperação. {@code maxChunkChars} é o teto de caracteres de um trecho (um bloco de código único pode
 * passar, porque cortar código no meio o inutiliza). {@code searchDepth} é a profundidade de cada lista da primeira
 * etapa (BM25 e kNN) antes da fusão por RRF; {@code candidates} é quantos trechos já fundidos seguem para o reranker, e
 * é ele, não a profundidade, que custa latência. {@code minScore} é o limiar de relevância do melhor trecho para a
 * pergunta ir ao modelo (score do reranker); {@code minScoreWithoutReranker} substitui o primeiro quando o reranker
 * está desligado.
 */
@Validated
@ConfigurationProperties("sage.retrieval")
public record RetrievalProperties(
        @Min(100) @DefaultValue("1500") int maxChunkChars,
        @Min(1) @DefaultValue("12") int candidates,
        @Min(1) @DefaultValue("5") int contextChunks,

        @DecimalMin("0.0") @DecimalMax("1.0") @DefaultValue("0.35")
        double minScore,

        @DecimalMin("0.0") @DecimalMax("1.0") @DefaultValue("0.0")
        double minScoreWithoutReranker,

        @Min(4) @DefaultValue("8") int comparisonMaxChunks,
        @Min(1) @DefaultValue("30") int searchDepth) {

    /** Profundidade padrão da primeira etapa (BM25 e kNN, antes da fusão). */
    public static final int DEFAULT_SEARCH_DEPTH = 30;

    @ConstructorBinding
    public RetrievalProperties {}

    /** Com a profundidade de busca padrão ({@value #DEFAULT_SEARCH_DEPTH}). */
    public RetrievalProperties(
            int maxChunkChars,
            int candidates,
            int contextChunks,
            double minScore,
            double minScoreWithoutReranker,
            int comparisonMaxChunks) {
        this(
                maxChunkChars,
                candidates,
                contextChunks,
                minScore,
                minScoreWithoutReranker,
                comparisonMaxChunks,
                DEFAULT_SEARCH_DEPTH);
    }
}
