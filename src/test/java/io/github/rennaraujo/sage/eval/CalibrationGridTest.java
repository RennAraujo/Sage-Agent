package io.github.rennaraujo.sage.eval;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rennaraujo.sage.question.QuestionAnalysis;
import io.github.rennaraujo.sage.search.ScoredChunk;
import io.github.rennaraujo.sage.search.SearchFilter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.SortedSet;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Grade de calibração de {@code sage.retrieval.search-depth}, {@code sage.retrieval.candidates} e
 * {@code sage.rerank.max-tokens}, no acervo normal e numa variante com cada seção engordada até perto
 * do teto de 1500 caracteres (os trechos reais são mais longos que os do acervo de avaliação, e o
 * corte de tokens do reranker só aparece neles). Para cada ponto refaz a recuperação das perguntas
 * simples e mede o acerto do documento esperado no top-5 e o tempo de busca mais reranker. É uma
 * ferramenta de calibração, não uma regressão: só roda com {@code -Deval.grid=true}, porque cada ponto
 * da grade abre os modelos de novo.
 */
@Tag("models")
@Tag("eval")
@EnabledIf("modelsPresent")
@EnabledIfSystemProperty(named = "eval.grid", matches = "true")
class CalibrationGridTest {

    private static final int[] DEPTHS = {12, 30};
    private static final int[] CANDIDATES = {6, 8, 12};
    private static final int[] TOKENS = {128, 256};
    private static final int WARMUP = 4;
    private static final String NL = String.valueOf((char) 10);

    static boolean modelsPresent() {
        return EvalModels.embeddingsAndReranker();
    }

    @Test
    void gridOfDepthCandidatesAndRerankTokens() {
        List<EvalCase> simple = EvalRuns.cases().stream()
                .filter(c -> c.expectedDocs().size() == 1)
                .filter(c -> "FAST_MODEL".equals(c.expectedRoute()))
                .toList();
        StringBuilder table =
                new StringBuilder("| Trechos | Profundidade | Candidatos | max-tokens | Acerto no top-5 | "
                        + "Busca + reranker p50 | p95 |" + NL + "| --- | --- | --- | --- | --- | --- | --- |" + NL);
        for (boolean longChunks : new boolean[] {false, true}) {
            for (int depth : DEPTHS) {
                for (int candidates : CANDIDATES) {
                    for (int tokens : TOKENS) {
                        table.append(measure(simple, depth, candidates, tokens, longChunks))
                                .append(NL);
                    }
                }
            }
        }
        EvalReport.section(
                "75",
                "Grade de calibração: profundidade, candidatos e `sage.rerank.max-tokens`",
                simple.size() + " perguntas simples (um documento esperado, rota rápida). \"Longos\" são os mesmos "
                        + "documentos com cada seção engordada até ~1400 caracteres. Tempo de `Retriever.retrieve` por "
                        + "pergunta, depois de " + WARMUP + " de aquecimento." + NL + NL + table);
        assertThat(table.toString()).contains("| curtos | 30 | 12 | 256 |");
    }

    private static String measure(List<EvalCase> simple, int depth, int candidates, int tokens, boolean longChunks) {
        EvalStack stack = EvalStack.custom(depth, candidates, tokens, longChunks);
        try {
            int hits = 0;
            List<Double> times = new ArrayList<>();
            for (int i = 0; i < simple.size(); i++) {
                EvalCase c = simple.get(i);
                QuestionAnalysis analysis = stack.analyzer().analyze(c.question(), Optional.empty());
                SearchFilter filter =
                        new SearchFilter(analysis.sources(), analysis.language(), versions(stack, analysis));
                long start = System.nanoTime();
                List<ScoredChunk> found = stack.retriever().retrieve(c.question(), filter);
                double millis = (System.nanoTime() - start) / 1_000_000.0;
                if (i >= WARMUP) {
                    times.add(millis);
                }
                if (found.stream()
                        .anyMatch(f -> f.chunk()
                                .documentId()
                                .value()
                                .equals(c.expectedDocs().get(0)))) {
                    hits++;
                }
            }
            return EvalReport.row(
                    longChunks ? "longos" : "curtos",
                    depth,
                    candidates,
                    tokens,
                    EvalReport.percent(hits, simple.size()),
                    EvalReport.ms(EvalReport.percentile(times, 50)),
                    EvalReport.ms(EvalReport.percentile(times, 95)));
        } finally {
            stack.close();
        }
    }

    private static List<String> versions(EvalStack stack, QuestionAnalysis analysis) {
        if (!analysis.versions().isEmpty() || analysis.language().isEmpty()) {
            return analysis.versions();
        }
        SortedSet<String> available =
                stack.index().versionsOf(analysis.language().get());
        return available.isEmpty() ? List.of() : List.of(available.last());
    }
}
