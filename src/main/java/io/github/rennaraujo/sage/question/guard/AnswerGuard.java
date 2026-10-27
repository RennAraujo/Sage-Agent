package io.github.rennaraujo.sage.question.guard;

import io.github.rennaraujo.sage.question.llm.PromptBuilder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Filtro contra delírio, aplicado frase a frase durante o streaming. Só passa frase com citação válida. Bloco de código
 * só passa se vier logo depois de uma frase aprovada (é ela que o justifica e o cita), se estiver fechado e se não for
 * prosa disfarçada de código; cada bloco consome a frase que o justificou, então dois blocos seguidos precisam de duas
 * frases citadas. O que é descartado nunca chega ao usuário e só entra na contagem. Se nada passar, o
 * {@link #finish()} devolve o texto fixo de "não cobre". Uma instância por resposta; não é thread-safe.
 *
 * <p>Prosa disfarçada: bloco marcado como {@code text}, {@code markdown}, {@code md}, {@code plain}, {@code plaintext}
 * ou {@code txt} é sempre recusado, porque não é código e escaparia da exigência de citação. Bloco sem linguagem é
 * recusado quando mais da metade das linhas não vazias termina em ponto, exclamação ou interrogação, que é como prosa
 * termina e código quase nunca. Blocos com linguagem de programação ou {@code mermaid} não passam por essa heurística.
 */
public final class AnswerGuard {

    private static final Set<String> PROSE_TAGS = Set.of("text", "markdown", "md", "plain", "plaintext", "txt");
    private static final int FENCE_LENGTH = 3;

    private final SentenceSplitter splitter = new SentenceSplitter();
    private final CitationValidator validator;
    private final SortedSet<Integer> cited = new TreeSet<>();
    private int accepted;
    private int dropped;
    private boolean previousAccepted;

    public AnswerGuard(int contextSize) {
        this.validator = new CitationValidator(contextSize);
    }

    /** Recebe um pedaço do texto do modelo e devolve as frases que ficaram completas e foram aprovadas. */
    public List<String> accept(String delta) {
        return judge(splitter.push(delta));
    }

    /** Fecha o stream: avalia o que sobrou e, se nada foi aprovado, devolve o texto de "não cobre". */
    public List<String> finish() {
        List<String> out = judge(splitter.flush());
        if (accepted == 0) {
            out.add(PromptBuilder.NOT_COVERED);
        }
        return out;
    }

    public int dropped() {
        return dropped;
    }

    /** Índices citados pelas frases aprovadas até agora, em ordem crescente. */
    public SortedSet<Integer> cited() {
        return Collections.unmodifiableSortedSet(cited);
    }

    /** Citações {@code [n]} de um texto, na ordem em que aparecem; colchetes em código inline não contam. */
    public static List<Integer> citationsIn(String text) {
        return CitationValidator.citations(text);
    }

    private List<String> judge(List<SentenceSplitter.Segment> segments) {
        List<String> out = new ArrayList<>();
        for (SentenceSplitter.Segment segment : segments) {
            String text = segment.text();
            if (segment.code()) {
                take(out, text, previousAccepted && segment.closed() && !disguisedProse(text));
                previousAccepted = false;
            } else if (PromptBuilder.NOT_COVERED.equals(text)) {
                // Resposta prevista para "não está nos documentos": o finish() a emite se nada mais passar.
                previousAccepted = false;
            } else {
                boolean ok = validator.valid(text);
                if (ok) {
                    cited.addAll(CitationValidator.citations(text));
                }
                take(out, text, ok);
                previousAccepted = ok;
            }
        }
        return out;
    }

    private void take(List<String> out, String text, boolean ok) {
        if (ok) {
            out.add(text);
            accepted++;
        } else {
            dropped++;
        }
    }

    static boolean disguisedProse(String block) {
        String[] lines = block.split("\n", -1);
        String tag = lines[0].substring(FENCE_LENGTH).strip().toLowerCase(Locale.ROOT);
        if (PROSE_TAGS.contains(tag)) {
            return true;
        }
        if (!tag.isEmpty()) {
            return false;
        }
        int total = 0;
        int prose = 0;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty() || line.startsWith("```")) {
                continue;
            }
            total++;
            char last = line.charAt(line.length() - 1);
            if (last == '.' || last == '!' || last == '?') {
                prose++;
            }
        }
        return total > 0 && prose * 2 > total;
    }
}
