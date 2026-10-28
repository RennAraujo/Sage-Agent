package io.github.rennaraujo.sage.eval;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rennaraujo.sage.eval.EvalStack.Outcome;
import io.github.rennaraujo.sage.eval.EvalStack.Session;
import io.github.rennaraujo.sage.question.guard.AnswerGuardFactory;
import io.github.rennaraujo.sage.question.guard.IdentifierCheck;
import io.github.rennaraujo.sage.question.guard.NliFiles;
import io.github.rennaraujo.sage.question.guard.OnnxNliChecker;
import io.github.rennaraujo.sage.question.guard.PremiseWindows;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * O {@code AnswerService} com o guard de produção (NLI real, valores do {@code application.yml}) e um modelo de
 * mentira que só diz coisas corretas e citadas, copiando o trecho ou parafraseando em pt-BR. Mede que fração das frases
 * corretas o filtro mantém; o piso é 85%. Roda só com {@code -Peval}.
 */
@Tag("models")
@Tag("eval")
@EnabledIf("modelsPresent")
class GuardEndToEndTest {

    private static final double KEPT_FLOOR = 0.85;
    private static final int MIN_IN_CONTEXT_SHARE_DENOMINATOR = 3;

    static boolean modelsPresent() {
        return EvalModels.embeddingsAndReranker();
    }

    @Test
    void productionGuardKeepsTheCorrectCitedSentences() {
        EvalModels.requireNli();
        EvalStack stack = EvalStack.get();
        AnswerGuardFactory guards = stack.withChecker(stack.nli());
        double minScore = stack.retrieval().minScore();

        // Frases copiadas do trecho: o modelo de mentira cita a primeira frase do melhor trecho.
        List<String> literalLost = new ArrayList<>();
        int literalTotal = countFastCases();
        int literalKept = literalTotal - lostCopies(stack, guards, minScore, literalLost);
        // O mesmo, sem o atalho de cópia de frase completa: quanto do resultado é do atalho e quanto é do NLI.
        int withoutShortcutLost;
        try (OnnxNliChecker plain = new OnnxNliChecker(NliFiles.inDir(stack.modelsDir()), stack.guard(), false)) {
            withoutShortcutLost = lostCopies(stack, stack.withChecker(plain), minScore, new ArrayList<>());
        }
        int withoutShortcutKept = literalTotal - withoutShortcutLost;

        // Paráfrases em pt-BR escritas à mão: a pergunta é a própria frase, e o fake a devolve citando o trecho certo.
        Map<String, String> paraphrases = new LinkedHashMap<>();
        List<NliPair> supported =
                NliPair.load(stack).stream().filter(NliPair::supported).toList();
        supported.forEach(p -> paraphrases.put(p.docId() + "|" + p.heading(), p.sentence()));
        Session paraphrasing = stack.session(new QuotingModel(paraphrases), guards, minScore);
        int paraphraseTotal = 0;
        int paraphraseKept = 0;
        List<String> lost = new ArrayList<>();
        for (NliPair pair : supported) {
            Outcome o = paraphrasing.ask(pair.sentence());
            boolean inContext = o.first().citations().stream()
                    .anyMatch(c -> c.documentId().value().equals(pair.docId())
                            && c.section().equals(pair.heading()));
            if (!inContext) {
                continue;
            }
            paraphraseTotal++;
            String bare = pair.sentence().replaceAll("[.]+$", "");
            if (o.texts().stream().anyMatch(t -> t.startsWith(bare))) {
                paraphraseKept++;
            } else {
                lost.add(pair.sentence()
                        + (IdentifierCheck.allKnown(pair.sentence(), List.of(pair.evidence()))
                                ? " [reprovada pelo NLI]"
                                : " [reprovada pelo identificador de código]"));
            }
        }
        String nl = String.valueOf((char) 10);
        EvalReport.section(
                "85",
                "Guard de produção (NLI real) de ponta a ponta",
                "O `AnswerService` com o `OnnxNliChecker` de produção (`min-entailment` "
                        + stack.guard().minEntailment() + ", janelas de " + PremiseWindows.WINDOW
                        + " frases por trecho citado, cópia literal aceita) e o modelo de "
                        + "mentira respondendo só com frases corretas e citadas. Piso: 85%." + nl + nl
                        + "| Resposta do modelo | Frases mantidas |" + nl + "| --- | --- |" + nl
                        + EvalReport.row(
                                "copia a primeira frase do melhor trecho",
                                EvalReport.percent(literalKept, literalTotal))
                        + nl
                        + EvalReport.row(
                                "copia a primeira frase, sem o atalho de cópia (só NLI)",
                                EvalReport.percent(withoutShortcutKept, literalTotal))
                        + nl
                        + EvalReport.row(
                                "paráfrase em pt-BR escrita à mão", EvalReport.percent(paraphraseKept, paraphraseTotal))
                        + nl + nl + list("Cópias descartadas", literalLost) + nl
                        + list("Paráfrases descartadas", lost));

        assertThat(paraphraseTotal).isGreaterThanOrEqualTo(supported.size() * 2 / MIN_IN_CONTEXT_SHARE_DENOMINATOR);
        assertThat((double) literalKept / literalTotal)
                .as("cópias mantidas, perdidas %s", literalLost)
                .isGreaterThanOrEqualTo(KEPT_FLOOR);
        assertThat((double) paraphraseKept / paraphraseTotal)
                .as("paráfrases mantidas, perdidas %s", lost)
                .isGreaterThanOrEqualTo(KEPT_FLOOR);
    }

    private static String list(String title, List<String> items) {
        if (items.isEmpty()) {
            return title + ": nenhuma.";
        }
        StringBuilder out = new StringBuilder(title + ":" + String.valueOf((char) 10) + String.valueOf((char) 10));
        items.forEach(i -> out.append("- ").append(i).append((char) 10));
        return out.toString();
    }

    private static int countFastCases() {
        return (int) EvalRuns.cases().stream()
                .filter(c -> "FAST_MODEL".equals(c.expectedRoute()))
                .count();
    }

    /** Quantas das perguntas rápidas perderam a frase copiada; as perdidas entram em {@code lostQuestions}. */
    private static int lostCopies(
            EvalStack stack, AnswerGuardFactory guards, double minScore, List<String> lostQuestions) {
        Session literal = stack.session(new QuotingModel(QuotingModel.Mode.QUOTE_TOP), guards, minScore);
        int lost = 0;
        for (EvalCase c : EvalRuns.cases()) {
            if (!"FAST_MODEL".equals(c.expectedRoute())) {
                continue;
            }
            if (literal.ask(c.question()).done().droppedSentences() != 0) {
                lost++;
                lostQuestions.add(c.question());
            }
        }
        return lost;
    }
}
