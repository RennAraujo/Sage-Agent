package io.github.rennaraujo.sage.search.rerank;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * {@code sage.rerank.max-tokens}: tamanho máximo do par (pergunta + trecho) em tokens. O custo do reranker é linear
 * no total de tokens do lote, então este é o botão de latência; o modelo aceita até 512.
 */
@Validated
@ConfigurationProperties("sage.rerank")
public record RerankProperties(
        @Min(64) @Max(512) @DefaultValue("256") int maxTokens) {}
