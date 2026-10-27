package io.github.rennaraujo.sage.question.guard;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Filtro de saída da resposta.
 *
 * <p>{@code sage.guard.min-entailment}: probabilidade mínima de "entailment" (softmax) para uma frase contar como
 * sustentada por um trecho. {@code sage.guard.nli-max-tokens}: tamanho máximo do par (trecho + frase) em tokens; o
 * custo do NLI é linear nele, e o modelo aceita até 512.
 *
 * <p>{@code sage.guard.require-nli}: se {@code true} e o modelo de NLI não está no disco, a aplicação não sobe (em vez
 * de degradar para só citações e segredos); produção deve ligar.
 *
 * <p>{@code sage.guard.nli-timeout-per-sentence}: prazo da verificação de uma frase (positivo); estourou, a frase é
 * descartada (falha fechado) e contada como descartada. {@code sage.guard.nli-max-concurrency}: quantas inferências do
 * NLI podem estar em curso ao mesmo tempo, contando as que já estouraram o prazo e ainda não terminaram; 0 (padrão)
 * significa metade dos núcleos, ao menos 1. Sem permissão dentro do prazo, a frase é descartada.
 */
@Validated
@ConfigurationProperties("sage.guard")
public record GuardProperties(
        @DecimalMin("0.0") @DecimalMax("1.0") @DefaultValue("0.5")
        double minEntailment,

        @Min(64) @Max(512) @DefaultValue("256") int nliMaxTokens,
        @DefaultValue("false") boolean requireNli,
        @DefaultValue("1500ms") Duration nliTimeoutPerSentence,
        @Min(0) @DefaultValue("0") int nliMaxConcurrency) {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofMillis(1500);

    @ConstructorBinding
    public GuardProperties {
        if (nliTimeoutPerSentence == null) {
            nliTimeoutPerSentence = DEFAULT_TIMEOUT;
        }
        if (nliTimeoutPerSentence.isZero() || nliTimeoutPerSentence.isNegative()) {
            throw new IllegalArgumentException("sage.guard.nli-timeout-per-sentence precisa ser positivo");
        }
        if (nliMaxConcurrency < 0) {
            throw new IllegalArgumentException("sage.guard.nli-max-concurrency não pode ser negativo");
        }
        if (nliMaxConcurrency == 0) {
            nliMaxConcurrency = defaultConcurrency();
        }
    }

    /** Com o prazo por frase padrão (1500 ms) e a concorrência padrão. */
    public GuardProperties(double minEntailment, int nliMaxTokens, boolean requireNli) {
        this(minEntailment, nliMaxTokens, requireNli, DEFAULT_TIMEOUT, 0);
    }

    /** Com a concorrência padrão. */
    public GuardProperties(double minEntailment, int nliMaxTokens, boolean requireNli, Duration nliTimeoutPerSentence) {
        this(minEntailment, nliMaxTokens, requireNli, nliTimeoutPerSentence, 0);
    }

    /** Metade dos núcleos disponíveis, ao menos 1. */
    static int defaultConcurrency() {
        return Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
    }
}
