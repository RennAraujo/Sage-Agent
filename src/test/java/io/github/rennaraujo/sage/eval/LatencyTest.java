package io.github.rennaraujo.sage.eval;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rennaraujo.sage.eval.EvalStack.Outcome;
import io.github.rennaraujo.sage.eval.EvalStack.Session;
import io.github.rennaraujo.sage.question.guard.OnnxNliChecker;
import io.github.rennaraujo.sage.question.guard.PremiseWindows;
import io.github.rennaraujo.sage.search.SearchFilter;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Latência do pipeline antes do modelo de linguagem (análise, busca híbrida, reranker, decisão de rota, expansão de
 * fluxo e auditoria) e do NLI por frase. Os números dependem da máquina; o relatório registra a usada. Roda só com
 * {@code -Peval}.
 */
@Tag("models")
@Tag("eval")
@EnabledIf("modelsPresent")
class LatencyTest {

    private static final Logger LOG = LoggerFactory.getLogger(LatencyTest.class);
    private static final int QUESTIONS = 30;
    private static final int WARMUP = 6;
    private static final double P50_BUDGET_MILLIS = 200;
    private static final int NLI_SENTENCES = 30;
    private static final int NLI_WARMUP = 3;
    private static final int FIVE = 5;
    private static final int FIVE_STEP = 7;

    static boolean modelsPresent() {
        return EvalModels.embeddingsAndReranker();
    }

    @Test
    void pipelineBeforeModelP50Under200ms() {
        EvalStack stack = EvalStack.get();
        List<EvalCase> cases = EvalRuns.cases();
        // Perguntas distintas das medidas servem de aquecimento (JIT, caches nativos do ONNX Runtime).
        Session warm = stack.session(new QuotingModel(QuotingModel.Mode.QUOTE_TOP));
        for (int i = 0; i < WARMUP; i++) {
            warm.ask(cases.get(i * 5 % cases.size()).question());
        }
        Session session = stack.session(new QuotingModel(QuotingModel.Mode.QUOTE_TOP));
        List<EvalCase> sample = sample(cases, QUESTIONS);
        List<Double> pipeline = new ArrayList<>();
        List<Double> search = new ArrayList<>();
        List<Double> retrieve = new ArrayList<>();
        for (EvalCase c : sample) {
            Outcome outcome = session.ask(c.question());
            pipeline.add(outcome.preModelMillis());
            search.add(millis(() -> stack.index()
                    .search(c.question(), SearchFilter.none(), stack.retrieval().candidates())));
            retrieve.add(millis(() -> stack.retriever().retrieve(c.question(), SearchFilter.none())));
        }
        double p50 = EvalReport.percentile(pipeline, 50);
        double p95 = EvalReport.percentile(pipeline, 95);
        EvalReport.section(
                "90",
                "Latência do pipeline antes do modelo",
                "Máquina: " + Runtime.getRuntime().availableProcessors() + " processadores lógicos, "
                        + System.getProperty("os.name") + ", Java " + System.getProperty("java.version") + ". "
                        + QUESTIONS + " perguntas do gabarito depois de " + WARMUP + " de aquecimento. Com "
                        + stack.retrieval().candidates() + " candidatos e `sage.rerank.max-tokens` "
                        + stack.rerank().maxTokens() + ". Do início de `answer` até a chamada ao modelo (ou ao "
                        + "`metadata`, quando não há modelo).\n\n| Etapa | p50 | p95 |\n| --- | --- | --- |\n"
                        + EvalReport.row("Pipeline antes do modelo", EvalReport.ms(p50), EvalReport.ms(p95)) + "\n"
                        + EvalReport.row(
                                "Busca híbrida (BM25 + vetores, com embedding da pergunta)",
                                EvalReport.ms(EvalReport.percentile(search, 50)),
                                EvalReport.ms(EvalReport.percentile(search, 95)))
                        + "\n"
                        + EvalReport.row(
                                "Busca mais reranker (`Retriever.retrieve`)",
                                EvalReport.ms(EvalReport.percentile(retrieve, 50)),
                                EvalReport.ms(EvalReport.percentile(retrieve, 95)))
                        + "\n");
        LOG.info("pipeline antes do modelo: p50 {} ms, p95 {} ms", Math.round(p50), Math.round(p95));

        assertThat(p50).as("p50 do pipeline antes do modelo em ms").isLessThan(P50_BUDGET_MILLIS);
    }

    @Test
    void nliPerSentenceStaysUnderThePerSentenceDeadline() {
        EvalStack stack = EvalStack.get();
        EvalModels.requireNli();
        // Frases do conjunto rotulado (paráfrases e afirmações erradas): passam pelo modelo, ao contrário de cópias
        // literais, que o checker aceita sem inferência.
        List<NliPair> pairs = NliPair.load(stack);
        assertThat(pairs).hasSizeGreaterThanOrEqualTo(NLI_SENTENCES);
        OnnxNliChecker nli = stack.nli();
        for (int i = 0; i < NLI_WARMUP; i++) {
            nli.isSupported(pairs.get(i).sentence(), List.of(pairs.get(i).evidence()));
        }
        List<Double> oneChunk = new ArrayList<>();
        List<Double> fiveChunks = new ArrayList<>();
        int stride = pairs.size() / NLI_SENTENCES;
        for (int k = 0; k < NLI_SENTENCES; k++) {
            int at = k * stride;
            NliPair pair = pairs.get(at);
            List<String> five = new ArrayList<>(List.of(pair.evidence()));
            for (int j = 1; j < FIVE; j++) {
                five.add(pairs.get((at + j * FIVE_STEP) % pairs.size()).evidence());
            }
            oneChunk.add(millis(() -> nli.isSupported(pair.sentence(), List.of(pair.evidence()))));
            fiveChunks.add(millis(() -> nli.isSupported(pair.sentence(), five)));
        }
        double deadline = stack.guard().nliTimeoutPerSentence().toMillis();
        String nl = String.valueOf((char) 10);
        EvalReport.section(
                "91",
                "Latência do NLI por frase",
                NLI_SENTENCES + " frases do conjunto rotulado, com `nli-max-tokens` "
                        + stack.guard().nliMaxTokens()
                        + ", janelas de " + PremiseWindows.WINDOW + " frases e `sage.guard.nli-max-concurrency` "
                        + stack.guard().nliMaxConcurrency() + "." + nl + nl
                        + "| Trechos citados | p50 | p95 | Prazo por frase |" + nl + "| --- | --- | --- | --- |" + nl
                        + EvalReport.row(
                                1,
                                EvalReport.ms(EvalReport.percentile(oneChunk, 50)),
                                EvalReport.ms(EvalReport.percentile(oneChunk, 95)),
                                EvalReport.ms(deadline))
                        + nl
                        + EvalReport.row(
                                5,
                                EvalReport.ms(EvalReport.percentile(fiveChunks, 50)),
                                EvalReport.ms(EvalReport.percentile(fiveChunks, 95)),
                                EvalReport.ms(deadline))
                        + nl);
        LOG.info(
                "nli por frase: 1 trecho p50 {} ms; 5 trechos p50 {} ms, p95 {} ms",
                Math.round(EvalReport.percentile(oneChunk, 50)),
                Math.round(EvalReport.percentile(fiveChunks, 50)),
                Math.round(EvalReport.percentile(fiveChunks, 95)));

        assertThat(EvalReport.percentile(fiveChunks, 95))
                .as("p95 do NLI por frase com 5 trechos, em ms")
                .isLessThan(deadline);
    }

    /** {@code count} perguntas espalhadas pelo gabarito, sem as de versão ausente (que nem chegam à busca). */
    private static List<EvalCase> sample(List<EvalCase> cases, int count) {
        List<EvalCase> searchable = cases.stream()
                .filter(c -> !c.expectedRoute().equals(EvalCase.NOT_DOCUMENTED) || c.outOfScope())
                .toList();
        List<EvalCase> sample = new ArrayList<>();
        double step = (double) searchable.size() / count;
        for (int i = 0; i < count; i++) {
            sample.add(searchable.get((int) (i * step)));
        }
        return sample;
    }

    private static double millis(Runnable task) {
        long start = System.nanoTime();
        task.run();
        return (System.nanoTime() - start) / 1_000_000.0;
    }
}
