package io.github.rennaraujo.sage.eval;

import io.github.rennaraujo.sage.eval.EvalStack.Outcome;
import java.util.ArrayList;
import java.util.List;

/**
 * Executa as perguntas-gabarito uma vez por JVM e guarda os resultados: recuperação, rota e pipeline saem da mesma
 * passagem, e a avaliação não paga o reranker de novo em cada teste. {@link #atProduction()} usa o limiar de
 * {@code application.yml}; {@link #withoutThreshold()} usa limiar 0 para medir o score do melhor trecho de cada
 * pergunta, que é a entrada da varredura de calibração.
 */
public final class EvalRuns {

    private static List<EvalCase> cases;
    private static List<Outcome> production;
    private static List<Outcome> unfiltered;

    private EvalRuns() {}

    public static synchronized List<EvalCase> cases() {
        if (cases == null) {
            cases = EvalCase.load();
        }
        return cases;
    }

    public static synchronized List<Outcome> atProduction() {
        if (production == null) {
            EvalStack stack = EvalStack.get();
            production = run(stack.session(new QuotingModel(QuotingModel.Mode.QUOTE_TOP)));
        }
        return production;
    }

    public static synchronized List<Outcome> withoutThreshold() {
        if (unfiltered == null) {
            EvalStack stack = EvalStack.get();
            unfiltered =
                    run(stack.session(new QuotingModel(QuotingModel.Mode.QUOTE_TOP), EvalStack.citationsOnly(), 0.0));
        }
        return unfiltered;
    }

    private static List<Outcome> run(EvalStack.Session session) {
        List<Outcome> outcomes = new ArrayList<>();
        for (EvalCase c : cases()) {
            outcomes.add(session.ask(c.question()));
        }
        return List.copyOf(outcomes);
    }
}
