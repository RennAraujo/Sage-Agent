package io.github.rennaraujo.sage.web;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Limites da semeadura por importação. {@code maxDepth} é quantos cliques a partir da página raiz (0 = só a raiz);
 * {@code maxPages} é o teto de páginas baixadas por importação; {@code jobsKept} é quantos jobs ficam consultáveis em
 * memória (sai o mais antigo já terminado; job em andamento nunca sai); {@code maxConcurrentJobs} é quantos rodam ao
 * mesmo tempo (o seguinte recebe 429); {@code maxDuration} é o prazo total de um job.
 */
@Validated
@ConfigurationProperties("sage.import")
public record ImportProperties(
        @Min(0) @DefaultValue("2") int maxDepth,
        @Min(1) @DefaultValue("200") int maxPages,
        @Min(1) @DefaultValue("50") int jobsKept,
        @Min(1) @DefaultValue("2") int maxConcurrentJobs,
        @NotNull @DefaultValue("10m") Duration maxDuration) {

    @AssertTrue(message = "sage.import.max-duration deve ser maior que zero")
    public boolean isMaxDurationPositive() {
        return maxDuration != null && !maxDuration.isNegative() && !maxDuration.isZero();
    }
}
