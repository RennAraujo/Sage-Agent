package io.github.rennaraujo.sage.eval;

import io.github.rennaraujo.sage.eval.EvalStack.Outcome;
import io.github.rennaraujo.sage.question.Citation;
import io.github.rennaraujo.sage.question.guard.AnswerGuard;
import io.github.rennaraujo.sage.search.Chunk;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/** Uma frase citada pelo modelo de mentira, sem o marcador {@code [n]}, com o texto do trecho que ela cita. */
record QuotedSentence(String sentence, String evidence, String docId) {

    private static final Pattern MARKERS = Pattern.compile("\\s*\\[\\d+](?:\\[\\d+])*");

    /** As frases citadas das respostas do modelo rápido (fora de comparação e de fluxo). */
    static List<QuotedSentence> fromFastAnswers(EvalStack stack, List<Outcome> outcomes) {
        List<QuotedSentence> quoted = new ArrayList<>();
        for (Outcome o : outcomes) {
            if (!"FAST_MODEL".equals(o.done().route().name())) {
                continue;
            }
            of(stack, o).ifPresent(quoted::add);
        }
        return List.copyOf(quoted);
    }

    private static Optional<QuotedSentence> of(EvalStack stack, Outcome o) {
        for (String text : o.texts()) {
            List<Integer> numbers = AnswerGuard.citationsIn(text);
            if (numbers.isEmpty()) {
                continue;
            }
            Citation citation = o.first().citations().get(numbers.get(0) - 1);
            for (Chunk chunk : stack.index().chunksOf(citation.documentId())) {
                if (chunk.headingPath().equals(citation.section())) {
                    return Optional.of(new QuotedSentence(
                            MARKERS.matcher(text).replaceAll("").strip(),
                            chunk.headingPath() + "\n" + chunk.text(),
                            citation.documentId().value()));
                }
            }
        }
        return Optional.empty();
    }
}
