package io.github.rennaraujo.sage.testsupport;

/**
 * Orçamento de latência dos testes de modelo. Relógio de parede varia com a carga da máquina, então no {@code verify}
 * padrão só vale um teto de sanidade (10 s); o orçamento de verdade só é cobrado com {@code -Peval}, que liga
 * {@code sage.latency.strict}. Os números medidos vão para o log e para {@code target/eval-report.md}.
 */
public final class LatencyBudget {

    private static final long SANITY_MILLIS = 10_000;

    private LatencyBudget() {}

    /** O orçamento estrito com {@code -Peval}; o teto de sanidade (ou o estrito, se maior) no resto. */
    public static long millis(long strictMillis) {
        return Boolean.getBoolean("sage.latency.strict") ? strictMillis : Math.max(strictMillis, SANITY_MILLIS);
    }
}
