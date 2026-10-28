package io.github.rennaraujo.sage.eval;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Relatório em Markdown ({@code target/eval-report.md}) montado pelas avaliações. Cada teste entrega uma seção com um
 * título; o arquivo inteiro é reescrito a cada seção nova, então a ordem de execução das classes não importa.
 */
public final class EvalReport {

    private static final Path FILE = Path.of("target", "eval-report.md");
    private static final Map<String, String> SECTIONS = new TreeMap<>();

    private EvalReport() {}

    public static synchronized void section(String order, String title, String body) {
        SECTIONS.put(order, "## " + title + "\n\n" + body.strip() + "\n");
        write();
    }

    private static void write() {
        StringBuilder out = new StringBuilder("# Relatório de avaliação do Sage\n\n");
        out.append("Gerado por `.\\mvnw.cmd verify -Peval`. Os números vêm do acervo de `src/test/resources/eval`, ");
        out.append("com os modelos ONNX reais e um modelo de linguagem determinístico no lugar do Bedrock.\n\n");
        SECTIONS.values().forEach(s -> out.append(s).append('\n'));
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, out.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String percent(int hits, int total) {
        return total == 0 ? "n/a" : String.format(Locale.ROOT, "%d/%d = %.1f%%", hits, total, 100.0 * hits / total);
    }

    public static String ms(double value) {
        return String.format(Locale.ROOT, "%.0f ms", value);
    }

    /** Percentil por posição no vetor ordenado (método do vizinho mais próximo). */
    public static double percentile(List<Double> values, double p) {
        List<Double> sorted = values.stream().sorted().toList();
        if (sorted.isEmpty()) {
            return Double.NaN;
        }
        int rank = (int) Math.ceil(p / 100.0 * sorted.size());
        return sorted.get(Math.min(sorted.size() - 1, Math.max(0, rank - 1)));
    }

    /** Linha de tabela Markdown. */
    public static String row(Object... cells) {
        StringBuilder out = new StringBuilder("|");
        for (Object cell : cells) {
            out.append(' ').append(String.valueOf(cell).replace("|", "\\|")).append(" |");
        }
        return out.toString();
    }
}
