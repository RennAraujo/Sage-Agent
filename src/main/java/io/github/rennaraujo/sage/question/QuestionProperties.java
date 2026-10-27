package io.github.rennaraujo.sage.question;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * {@code sage.questions.*}. {@code preparationTimeout} é o orçamento de análise, busca e auditoria antes do modelo; o
 * stream SSE fica aberto por ele mais {@code sage.models.timeout}. {@code indexRetryAfter} é o {@code Retry-After}
 * devolvido enquanto o índice carrega.
 */
@Validated
@ConfigurationProperties("sage.questions")
public record QuestionProperties(
        @DefaultValue("20s") Duration preparationTimeout,
        @DefaultValue("5s") Duration indexRetryAfter) {}
