package io.github.rennaraujo.sage.search;

import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * Diz se o índice terminou a carga inicial. Também é o indicador de saúde "index": DOWN enquanto carrega (ou se a carga
 * falhou) e UP depois. Como o agregado de {@code /actuator/health} considera o pior estado, o serviço só se anuncia
 * saudável quando consegue responder busca.
 */
public final class IndexReadiness implements HealthIndicator {

    private enum State {
        LOADING("loading"),
        READY("ready"),
        FAILED("failed");

        private final String label;

        State(String label) {
            this.label = label;
        }
    }

    private final AtomicReference<State> state = new AtomicReference<>(State.LOADING);

    public boolean isReady() {
        return state.get() == State.READY;
    }

    void markReady() {
        state.set(State.READY);
    }

    void markFailed() {
        state.set(State.FAILED);
    }

    @Override
    public Health health() {
        State current = state.get();
        Health.Builder builder = current == State.READY ? Health.up() : Health.down();
        return builder.withDetail("state", current.label).build();
    }
}
