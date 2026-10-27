package io.github.rennaraujo.sage.search;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Parâmetros da recuperação. {@code maxChunkChars} é o teto de caracteres de um trecho (um bloco de código único pode
 * passar, porque cortar código no meio o inutiliza). {@code candidates} é quantos trechos a busca pede ao índice antes
 * de qualquer reordenação. {@code minScore} é o limiar de relevância do melhor trecho para a pergunta ir ao modelo
 * (score do reranker); {@code minScoreWithoutReranker} substitui o primeiro quando o reranker está desligado.
 */
@Validated
@ConfigurationProperties("sage.retrieval")
public record RetrievalProperties(
        @Min(100) @DefaultValue("1500") int maxChunkChars,
        @Min(1) @DefaultValue("20") int candidates,
        @Min(1) @DefaultValue("5") int contextChunks,

        @DecimalMin("0.0") @DecimalMax("1.0") @DefaultValue("0.35")
        double minScore,

        @DecimalMin("0.0") @DecimalMax("1.0") @DefaultValue("0.0")
        double minScoreWithoutReranker) {}
