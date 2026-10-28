package io.github.rennaraujo.sage.eval;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rennaraujo.sage.eval.EvalStack.Outcome;
import io.github.rennaraujo.sage.question.AnswerMetadata;
import io.github.rennaraujo.sage.question.Citation;
import io.github.rennaraujo.sage.question.ConsultedSource;
import io.github.rennaraujo.sage.question.QuestionAnalysis;
import io.github.rennaraujo.sage.question.guard.AnswerGuard;
import io.github.rennaraujo.sage.question.guard.NliFiles;
import io.github.rennaraujo.sage.question.guard.PremiseWindows;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Avaliação com as perguntas-gabarito de {@code eval/perguntas.yml}: recuperação, rota, versão, rótulos e o pipeline
 * completo com um modelo determinístico que cita o melhor trecho. Roda só com {@code -Peval} e com os modelos ONNX em
 * {@code sage.models.dir}; escreve as seções 1 a 5 de {@code target/eval-report.md}.
 */
@Tag("models")
@Tag("eval")
@EnabledIf("modelsPresent")
class EvalRunnerTest {

    private static final Logger LOG = LoggerFactory.getLogger(EvalRunnerTest.class);
    private static final double TARGET = 0.90;
    private static final String NL = String.valueOf((char) 10);
    /**
     * Perguntas de comparação em que a falsa ausência ("A documentação do Java 21 disponível não menciona
     * isso." sobre um assunto que a versão documenta) ainda acontece e que são conhecidas. Vazia: a regra
     * do melhor trecho híbrido por versão (ver docs/decisoes.md) elimina as do gabarito. Entrar aqui exige
     * uma linha em docs/decisoes.md.
     */
    private static final java.util.Set<String> KNOWN_FALSE_ABSENCE = java.util.Set.of();

    private static final int MIN_PER_SOURCE = 30;
    private static final int MIN_OUT_OF_SCOPE = 10;
    private static final double SWEEP_STEP = 0.05;
    private static final double SWEEP_MAX = 0.95;
    private static final Map<String, String> NAMES = Map.of("java", "Java", "spring-boot", "Spring Boot");
    private static final double NLI_STEP = 0.1;
    private static final double NLI_RECALL_TARGET = 0.85;
    private static final double NLI_REJECT_TARGET = 0.95;
    private static final int MIN_UNSUPPORTED_PAIRS = 30;

    static boolean modelsPresent() {
        return EvalModels.embeddingsAndReranker();
    }

    @Test
    void goldenSetIsLargeEnough() {
        List<EvalCase> cases = EvalRuns.cases();

        assertThat(cases.stream()
                        .filter(c -> EvalCase.PUBLIC.equals(c.source()))
                        .count())
                .isGreaterThanOrEqualTo(MIN_PER_SOURCE);
        assertThat(cases.stream()
                        .filter(c -> EvalCase.INTERNAL.equals(c.source()))
                        .count())
                .isGreaterThanOrEqualTo(MIN_PER_SOURCE);
        assertThat(cases.stream().filter(EvalCase::outOfScope).count()).isGreaterThanOrEqualTo(MIN_OUT_OF_SCOPE);
        assertThat(cases).allSatisfy(c -> assertThat(c.expectedRoute()).isNotBlank());
    }

    @Test
    void retrievalTop5ContainsExpectedDoc() {
        List<EvalCase> cases = EvalRuns.cases();
        List<Outcome> outcomes = EvalRuns.atProduction();
        int total = 0;
        int hits = 0;
        List<String> misses = new ArrayList<>();
        StringBuilder bySource = new StringBuilder();
        for (String source : List.of(EvalCase.PUBLIC, EvalCase.INTERNAL)) {
            int sourceTotal = 0;
            int sourceHits = 0;
            for (int i = 0; i < cases.size(); i++) {
                EvalCase c = cases.get(i);
                if (!source.equals(c.source()) || c.expectedDocs().isEmpty()) {
                    continue;
                }
                sourceTotal++;
                if (outcomes.get(i).contextDocs().containsAll(c.expectedDocs())) {
                    sourceHits++;
                } else {
                    misses.add(c.question() + " -> esperado " + c.expectedDocs() + ", veio "
                            + outcomes.get(i).contextDocs());
                }
            }
            total += sourceTotal;
            hits += sourceHits;
            bySource.append(EvalReport.row(source, EvalReport.percent(sourceHits, sourceTotal)))
                    .append('\n');
        }
        EvalReport.section(
                "20",
                "Recuperação: o documento esperado entre os trechos de contexto",
                "Meta: pelo menos 90%. Os trechos de contexto são os que o `AnswerService` envia ao modelo "
                        + "(5 por pergunta, ou até 8 numa comparação por versão), lidos da auditoria "
                        + "`QUESTION_ASKED`.\n\n"
                        + "| Fonte | Acerto |\n| --- | --- |\n" + bySource + "\nGeral: "
                        + EvalReport.percent(hits, total) + "\n\n" + listOf("Erros", misses));
        LOG.info("recuperação: {}", EvalReport.percent(hits, total));

        assertThat((double) hits / total)
                .as("recuperação %s, erros %s", EvalReport.percent(hits, total), misses)
                .isGreaterThanOrEqualTo(TARGET);
    }

    @Test
    void routesMatchExpected() {
        List<EvalCase> cases = EvalRuns.cases();
        List<Outcome> outcomes = EvalRuns.atProduction();
        Map<String, int[]> bySource = new java.util.TreeMap<>();
        List<String> misses = new ArrayList<>();
        int hits = 0;
        for (int i = 0; i < cases.size(); i++) {
            EvalCase c = cases.get(i);
            String actual = outcomes.get(i).done().route().name();
            int[] counts = bySource.computeIfAbsent(c.source(), k -> new int[2]);
            counts[1]++;
            if (actual.equals(c.expectedRoute())) {
                counts[0]++;
                hits++;
            } else {
                misses.add(c.question() + " -> esperado " + c.expectedRoute() + ", veio " + actual + " (score "
                        + String.format(
                                Locale.ROOT, "%.3f", outcomes.get(i).done().topScore()) + ")");
            }
        }
        StringBuilder rows = new StringBuilder();
        bySource.forEach((source, counts) -> rows.append(
                        EvalReport.row(source, EvalReport.percent(counts[0], counts[1])))
                .append('\n'));
        EvalReport.section(
                "30",
                "Rotas",
                "Meta: pelo menos 90%. `sage.retrieval.min-score` em uso: "
                        + EvalStack.get().retrieval().minScore() + ".\n\n| Fonte | Rota certa |\n| --- | --- |\n" + rows
                        + "\nGeral: " + EvalReport.percent(hits, cases.size()) + "\n\n" + listOf("Erros", misses));
        LOG.info("rotas: {}", EvalReport.percent(hits, cases.size()));

        assertThat((double) hits / cases.size())
                .as("rotas %s, erros %s", EvalReport.percent(hits, cases.size()), misses)
                .isGreaterThanOrEqualTo(TARGET);
    }

    @Test
    void versionsAreExtractedFromTheQuestion() {
        List<String> misses = new ArrayList<>();
        int total = 0;
        for (EvalCase c : EvalRuns.cases()) {
            if (c.versions().isEmpty()) {
                continue;
            }
            total++;
            QuestionAnalysis analysis = EvalStack.get().analyzer().analyze(c.question(), Optional.empty());
            if (!analysis.versions().equals(c.versions().get())) {
                misses.add(c.question() + " -> esperado " + c.versions().get() + ", veio " + analysis.versions());
            }
        }
        EvalReport.section(
                "40",
                "Versões extraídas da pergunta",
                "Casos com `versoes` no gabarito: " + EvalReport.percent(total - misses.size(), total) + "\n\n"
                        + listOf("Erros", misses));

        assertThat(total).isGreaterThanOrEqualTo(10);
        assertThat(misses).isEmpty();
    }

    @Test
    void versionLinesNoticesAndConsultedSourcesAreEmittedByTheCode() {
        List<EvalCase> cases = EvalRuns.cases();
        List<Outcome> outcomes = EvalRuns.atProduction();
        List<String> misses = new ArrayList<>();
        int checked = 0;
        for (int i = 0; i < cases.size(); i++) {
            EvalCase c = cases.get(i);
            Outcome o = outcomes.get(i);
            boolean relevant = !c.expectedTexts().isEmpty()
                    || !c.forbiddenTexts().isEmpty()
                    || !c.expectedConsulted().isEmpty()
                    || c.noConsulted();
            if (!relevant) {
                continue;
            }
            checked++;
            int cursor = -1;
            for (String text : c.expectedTexts()) {
                int at = o.texts().indexOf(text);
                if (at < 0 || at < cursor) {
                    misses.add(c.question() + " -> texto fixo ausente ou fora de ordem: " + text + " em " + o.texts());
                }
                cursor = Math.max(cursor, at);
            }
            for (String forbidden : c.forbiddenTexts()) {
                if (o.texts().contains(forbidden) && !KNOWN_FALSE_ABSENCE.contains(c.question())) {
                    misses.add(c.question() + " -> falsa ausência: " + forbidden);
                }
            }
            List<String> consulted = o.done().consulted().stream()
                    .map(EvalRunnerTest::consultedKey)
                    .toList();
            if (!c.expectedConsulted().isEmpty() && !consulted.equals(c.expectedConsulted())) {
                misses.add(c.question() + " -> documentacaoConsultada esperada " + c.expectedConsulted() + ", veio "
                        + consulted);
            }
            if (c.noConsulted() && !consulted.isEmpty()) {
                misses.add(c.question() + " -> documentacaoConsultada deveria vir vazio, veio " + consulted);
            }
        }
        EvalReport.section(
                "50",
                "Linhas de versão, avisos e `documentacaoConsultada`",
                "Casos verificados (texto fixo emitido pelo código, na ordem, e fontes do `done`): " + checked
                        + ". Falhas: " + misses.size() + ".\n\n" + listOf("Erros", misses));

        assertThat(checked).isGreaterThanOrEqualTo(15);
        assertThat(misses).isEmpty();
    }

    @Test
    void everyAnsweredQuestionCitesWithVersionAndAnnouncesItsSources() {
        List<EvalCase> cases = EvalRuns.cases();
        List<Outcome> outcomes = EvalRuns.atProduction();
        List<String> problems = new ArrayList<>();
        int answered = 0;
        for (int i = 0; i < cases.size(); i++) {
            Outcome o = outcomes.get(i);
            if (o.done().route().name().equals(EvalCase.NOT_DOCUMENTED)) {
                continue;
            }
            answered++;
            problems.addAll(pipelineProblems(cases.get(i), o));
        }
        EvalReport.section(
                "60",
                "Pipeline completo com o modelo que cita o melhor trecho",
                "Perguntas respondidas pelo modelo de mentira: " + answered + ". Invariantes conferidas em cada uma: "
                        + "toda frase do modelo traz `[n]` de um trecho do contexto, cada citação de documento público "
                        + "traz a versão do caminho do documento, a linha \"Documentação consultada\" vem antes da "
                        + "primeira frase que cita a fonte e `done.documentacaoConsultada` é exatamente a lista dessas "
                        + "linhas. Problemas: " + problems.size() + ".\n\n" + listOf("Problemas", problems));

        assertThat(answered).isGreaterThan(MIN_PER_SOURCE);
        assertThat(problems).isEmpty();
    }

    @Test
    void minScoreSweepShowsTheChosenThresholdIsOnThePlateau() {
        List<EvalCase> cases = EvalRuns.cases();
        List<Outcome> unfiltered = EvalRuns.withoutThreshold();
        double chosen = EvalStack.get().retrieval().minScore();
        StringBuilder table = new StringBuilder("| Limiar | Rota certa | Respondidas (dentro do acervo) | "
                + "Recusadas (fora do acervo) |\n| --- | --- | --- | --- |\n");
        double bestAccuracy = 0;
        double chosenAccuracy = 0;
        for (double t = SWEEP_STEP; t <= SWEEP_MAX + 1e-9; t += SWEEP_STEP) {
            int hits = 0;
            int inScope = 0;
            int answeredInScope = 0;
            int outScope = 0;
            int rejectedOut = 0;
            for (int i = 0; i < cases.size(); i++) {
                EvalCase c = cases.get(i);
                String actual = predicted(unfiltered.get(i), t);
                boolean right = actual.equals(c.expectedRoute());
                hits += right ? 1 : 0;
                if (c.outOfScope()) {
                    outScope++;
                    rejectedOut += actual.equals(EvalCase.NOT_DOCUMENTED) ? 1 : 0;
                } else if (!c.expectedRoute().equals(EvalCase.NOT_DOCUMENTED)) {
                    inScope++;
                    answeredInScope += right ? 1 : 0;
                }
            }
            double accuracy = (double) hits / cases.size();
            bestAccuracy = Math.max(bestAccuracy, accuracy);
            if (Math.abs(t - chosen) < SWEEP_STEP / 2) {
                chosenAccuracy = accuracy;
            }
            table.append(EvalReport.row(
                            String.format(Locale.ROOT, "%.2f", t)
                                    + (Math.abs(t - chosen) < SWEEP_STEP / 2 ? " (atual)" : ""),
                            EvalReport.percent(hits, cases.size()),
                            EvalReport.percent(answeredInScope, inScope),
                            EvalReport.percent(rejectedOut, outScope)))
                    .append('\n');
        }
        EvalReport.section(
                "70",
                "Calibração de `sage.retrieval.min-score`",
                "Cada linha refaz a decisão de rota com aquele limiar sobre o score do melhor trecho de cada pergunta "
                        + "(uma passagem com limiar 0). O score é o do reranker (`sigmoid` do logit do cross-encoder). "
                        + "Em uso: " + chosen + ".\n\n" + table + "\n" + scoreDistribution(cases, unfiltered));

        assertThat(chosenAccuracy).as("acerto no limiar em uso").isGreaterThanOrEqualTo(TARGET);
        assertThat(chosenAccuracy)
                .as("acerto no limiar em uso vs o melhor da varredura")
                .isGreaterThanOrEqualTo(bestAccuracy - 2.0 / cases.size());
    }

    @Test
    void nliThresholdSweepOnLabelledPairs() {
        EvalModels.requireNli();
        EvalStack stack = EvalStack.get();
        List<NliPair> pairs = NliPair.load(stack);
        List<QuotedSentence> verbatim = QuotedSentence.fromFastAnswers(stack, EvalRuns.atProduction());
        double chosen = stack.guard().minEntailment();
        StringBuilder variants = new StringBuilder("| Premissa | Sustentadas aceitas | Não sustentadas rejeitadas | "
                + "Cópias literais aceitas |" + NL + "| --- | --- | --- | --- |" + NL);
        List<Double> supported = new ArrayList<>();
        List<Double> unsupported = new ArrayList<>();
        List<Double> literal = new ArrayList<>();
        List<String> acceptedWrongly = new ArrayList<>();
        try (NliProbe probe =
                new NliProbe(NliFiles.inDir(stack.modelsDir()), stack.guard().nliMaxTokens())) {
            for (int window : new int[] {0, 1, 2, 3}) {
                List<Double> s = new ArrayList<>();
                List<Double> n = new ArrayList<>();
                List<Double> l = new ArrayList<>();
                for (NliPair pair : pairs) {
                    (pair.supported() ? s : n).add(probe.entailment(pair.evidence(), pair.sentence(), window));
                }
                for (QuotedSentence q : verbatim) {
                    l.add(probe.entailment(q.evidence(), q.sentence(), window));
                }
                variants.append(EvalReport.row(
                                window == 0 ? "trecho inteiro" : "janelas de " + window + " frase(s)",
                                share(s, chosen, true),
                                share(n, chosen, false),
                                share(l, chosen, true)))
                        .append(NL);
                if (window == PremiseWindows.WINDOW) {
                    supported = s;
                    unsupported = n;
                    literal = l;
                }
            }
            List<NliPair> negatives = pairs.stream().filter(p -> !p.supported()).toList();
            for (int i = 0; i < negatives.size(); i++) {
                if (unsupported.get(i) >= chosen) {
                    acceptedWrongly.add(negatives.get(i).sentence());
                }
            }
            // O verificador de produção decide igual à probabilidade lida aqui, salvo empate de arredondamento.
            for (NliPair pair : pairs) {
                double p = probe.entailment(pair.evidence(), pair.sentence());
                if (Math.abs(p - chosen) > 0.01) {
                    assertThat(stack.nli().isSupported(pair.sentence(), List.of(pair.evidence())))
                            .as("decisão do OnnxNliChecker com p=%.3f", p)
                            .isEqualTo(p >= chosen);
                }
            }
        }
        StringBuilder table = new StringBuilder("| min-entailment | Sustentadas aceitas (" + supported.size()
                + ") | Não sustentadas rejeitadas (" + unsupported.size() + ") | Cópias literais aceitas ("
                + literal.size() + ") |" + NL + "| --- | --- | --- | --- |" + NL);
        double acceptedAtChosen = 0;
        double rejectedAtChosen = 0;
        for (double t = NLI_STEP; t < 1.0; t += NLI_STEP) {
            final double limit = t;
            long accepted = supported.stream().filter(p -> p >= limit).count();
            long rejected = unsupported.stream().filter(p -> p < limit).count();
            long accepts = literal.stream().filter(p -> p >= limit).count();
            boolean current = Math.abs(t - chosen) < NLI_STEP / 2;
            if (current) {
                acceptedAtChosen = (double) accepted / supported.size();
                rejectedAtChosen = (double) rejected / unsupported.size();
            }
            table.append(EvalReport.row(
                            String.format(Locale.ROOT, "%.1f", t) + (current ? " (atual)" : ""),
                            EvalReport.percent((int) accepted, supported.size()),
                            EvalReport.percent((int) rejected, unsupported.size()),
                            EvalReport.percent((int) accepts, literal.size())))
                    .append(NL);
        }
        EvalReport.section(
                "80",
                "Calibração de `sage.guard.min-entailment` e das janelas de premissa",
                "Conjunto rotulado à mão em `eval/nli-pairs.tsv`: frases em pt-BR, como o modelo as escreveria, "
                        + "contra o trecho que ela cita (caminho de seções + texto). As não sustentadas são negações, "
                        + "versões "
                        + "trocadas, números trocados e nomes de API errados. A última coluna usa a primeira frase do "
                        + "melhor trecho copiada do próprio trecho. Limiar em uso: " + chosen + ", até "
                        + stack.guard().nliMaxTokens() + " tokens no par." + NL + NL
                        + "Estratégia de premissa no limiar em uso:" + NL + NL + variants + NL
                        + "Varredura do limiar com janelas de " + PremiseWindows.WINDOW + " frases:" + NL + NL + table
                        + NL + listOf("Não sustentadas aceitas no limiar em uso", acceptedWrongly));

        assertThat(unsupported).hasSizeGreaterThanOrEqualTo(MIN_UNSUPPORTED_PAIRS);
        assertThat(acceptedAtChosen)
                .as("sustentadas aceitas no limiar em uso")
                .isGreaterThanOrEqualTo(NLI_RECALL_TARGET);
        assertThat(rejectedAtChosen)
                .as("não sustentadas rejeitadas no limiar em uso, aceitas: %s", acceptedWrongly)
                .isGreaterThanOrEqualTo(NLI_REJECT_TARGET);
    }

    private static String share(List<Double> probabilities, double threshold, boolean accepted) {
        long hits =
                probabilities.stream().filter(p -> accepted == (p >= threshold)).count();
        return EvalReport.percent((int) hits, probabilities.size());
    }

    // ---------------------------------------------------------------------------------------- helpers

    private static String consultedKey(ConsultedSource source) {
        return source.version().map(v -> source.language() + " " + v).orElse(source.language());
    }

    /** O que a rota seria com o limiar {@code t}, dado o score do melhor trecho de uma passagem sem limiar. */
    private static String predicted(Outcome unfiltered, double t) {
        String route = unfiltered.done().route().name();
        if (route.equals(EvalCase.NOT_DOCUMENTED) || unfiltered.done().topScore() < t) {
            return EvalCase.NOT_DOCUMENTED;
        }
        return route;
    }

    private static String scoreDistribution(List<EvalCase> cases, List<Outcome> unfiltered) {
        List<Double> inScope = new ArrayList<>();
        List<Double> outScope = new ArrayList<>();
        for (int i = 0; i < cases.size(); i++) {
            EvalCase c = cases.get(i);
            if (c.expectedRoute().equals(EvalCase.NOT_DOCUMENTED) && !c.outOfScope()) {
                continue;
            }
            (c.outOfScope() ? outScope : inScope).add(unfiltered.get(i).done().topScore());
        }
        return "Score do melhor trecho, sem limiar.\n\n| Grupo | Mínimo | p10 | Mediana | Máximo |\n"
                + "| --- | --- | --- | --- | --- |\n"
                + distributionRow("Dentro do acervo", inScope) + "\n" + distributionRow("Fora do acervo", outScope)
                + "\n\nFora do acervo, por pergunta:\n\n"
                + cases.stream()
                        .filter(EvalCase::outOfScope)
                        .map(c -> "- "
                                + String.format(
                                        Locale.ROOT,
                                        "%.3f",
                                        unfiltered.get(cases.indexOf(c)).done().topScore())
                                + " " + c.question())
                        .reduce("", (a, b) -> a + b + "\n");
    }

    private static String distributionRow(String group, List<Double> values) {
        return EvalReport.row(
                group,
                score(EvalReport.percentile(values, 0)),
                score(EvalReport.percentile(values, 10)),
                score(EvalReport.percentile(values, 50)),
                score(EvalReport.percentile(values, 100)));
    }

    private static String score(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static String listOf(String title, List<String> items) {
        if (items.isEmpty()) {
            return title + ": nenhum.\n";
        }
        StringBuilder out = new StringBuilder(title + ":\n\n");
        items.forEach(item -> out.append("- ").append(item).append('\n'));
        return out.toString();
    }

    /** Invariantes do pipeline para uma pergunta que foi ao modelo. */
    static List<String> pipelineProblems(EvalCase c, Outcome o) {
        List<String> problems = new ArrayList<>();
        AnswerMetadata first = o.first();
        List<Citation> citations = first.citations();
        Set<String> expectedLines = new LinkedHashSet<>();
        Set<String> announced = new LinkedHashSet<>();
        for (Citation citation : citations) {
            if (citation.version().isPresent()
                    && !citation.documentId()
                            .value()
                            .contains("/" + citation.version().get() + "/")) {
                problems.add(c.question() + " -> versão da citação " + citation.index() + " não bate com o caminho");
            }
            if (citation.documentId().source() == io.github.rennaraujo.sage.document.Source.PUBLIC
                    && citation.version().isEmpty()) {
                problems.add(c.question() + " -> citação pública sem versão: " + citation.index());
            }
        }
        for (String text : o.texts()) {
            boolean fixed = text.startsWith("Documentação consultada: ")
                    || text.startsWith("Considerei a versão ")
                    || text.startsWith("Não tenho a documentação")
                    || text.startsWith("Comparei só")
                    || (text.startsWith("A documentação do ") && text.endsWith("não menciona isso."));
            List<Integer> numbers = AnswerGuard.citationsIn(text);
            if (fixed) {
                if (text.startsWith("Documentação consultada: ")) {
                    announced.add(text);
                }
                continue;
            }
            if (numbers.isEmpty()) {
                problems.add(c.question() + " -> frase sem citação chegou ao usuário: " + text);
                continue;
            }
            for (int n : numbers) {
                if (n < 1 || n > citations.size()) {
                    problems.add(c.question() + " -> citação fora do contexto: " + text);
                    continue;
                }
                String label = labelOf(citations.get(n - 1));
                String line = "Documentação consultada: " + label;
                expectedLines.add(line);
                if (!announced.contains(line)) {
                    problems.add(c.question() + " -> a frase cita " + label + " antes da linha de versão");
                }
            }
        }
        List<String> doneLabels = o.done().consulted().stream()
                .map(s -> s.source() == io.github.rennaraujo.sage.document.Source.INTERNAL
                        ? s.language() + " (interna)"
                        : s.name() + " " + s.version().orElse("(versão não informada)"))
                .toList();
        List<String> expectedLabels = expectedLines.stream()
                .map(l -> l.substring("Documentação consultada: ".length()))
                .toList();
        if (!doneLabels.equals(expectedLabels)) {
            problems.add(c.question() + " -> done.documentacaoConsultada " + doneLabels + " difere das linhas citadas "
                    + expectedLabels);
        }
        return problems;
    }

    private static String labelOf(Citation citation) {
        if (citation.documentId().source() == io.github.rennaraujo.sage.document.Source.INTERNAL) {
            return citation.language() + " (interna)";
        }
        return NAMES.getOrDefault(citation.language(), citation.language()) + " "
                + citation.version().orElse("(versão não informada)");
    }
}
