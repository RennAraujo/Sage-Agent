package io.github.rennaraujo.sage.question.guard;

import java.util.function.LongSupplier;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * Indicador de saúde "guard". Sempre UP: a falta do modelo de NLI degrada o guard, não derruba o serviço (quem quer
 * falhar na partida liga {@code sage.guard.require-nli}). O detalhe {@code nli} diz {@code active} ou {@code disabled},
 * e {@code nliTimeouts} e {@code nliSaturated} contam as frases descartadas por prazo e por saturação do verificador
 * (um número que cresce é sinal de que a CPU não dá conta ou de que {@code sage.guard.nli-max-concurrency} está baixo).
 * Os detalhes só aparecem em {@code /actuator/health} com {@code management.endpoint.health.show-details} habilitado.
 */
public final class GuardHealthIndicator implements HealthIndicator {

    private final boolean active;
    private final LongSupplier timeouts;
    private final LongSupplier saturated;

    public GuardHealthIndicator(GroundednessChecker checker) {
        this(checker, () -> 0, () -> 0);
    }

    public GuardHealthIndicator(GroundednessChecker checker, AnswerGuardFactory factory) {
        this(checker, factory::nliTimeouts, factory::nliSaturated);
    }

    private GuardHealthIndicator(GroundednessChecker checker, LongSupplier timeouts, LongSupplier saturated) {
        this.active = !(checker instanceof PassThroughGroundednessChecker);
        this.timeouts = timeouts;
        this.saturated = saturated;
    }

    @Override
    public Health health() {
        return Health.up()
                .withDetail("nli", active ? "active" : "disabled")
                .withDetail("nliTimeouts", timeouts.getAsLong())
                .withDetail("nliSaturated", saturated.getAsLong())
                .build();
    }
}
