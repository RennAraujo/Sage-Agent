package io.github.rennaraujo.sage.question.guard;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Confere as citações de uma frase: precisa ter ao menos uma {@code [n]} e todas devem apontar para um trecho que foi
 * de fato enviado (1 a {@code contextSize}). Colchetes dentro de código inline ({@code `args[0]`}) não contam.
 */
final class CitationValidator {

    private static final Pattern CITATION = Pattern.compile("\\[(\\d{1,4})]");
    private static final Pattern CITATION_WITH_SPACE = Pattern.compile("\\s*\\[\\d{1,4}]");
    private static final Pattern INLINE_CODE = Pattern.compile("`[^`]*`");

    private final int contextSize;

    CitationValidator(int contextSize) {
        this.contextSize = contextSize;
    }

    boolean valid(String sentence) {
        List<Integer> cited = citations(sentence);
        return !cited.isEmpty() && cited.stream().allMatch(n -> n >= 1 && n <= contextSize);
    }

    /** A frase sem os marcadores {@code [n]} (e o espaço antes deles); colchetes em código inline ficam como estão. */
    static String withoutCitations(String sentence) {
        StringBuilder out = new StringBuilder();
        Matcher code = INLINE_CODE.matcher(sentence);
        int from = 0;
        while (code.find()) {
            out.append(CITATION_WITH_SPACE
                    .matcher(sentence.substring(from, code.start()))
                    .replaceAll(""));
            out.append(code.group());
            from = code.end();
        }
        out.append(CITATION_WITH_SPACE.matcher(sentence.substring(from)).replaceAll(""));
        return out.toString().strip();
    }

    static List<Integer> citations(String sentence) {
        String prose = INLINE_CODE.matcher(sentence).replaceAll("");
        List<Integer> found = new ArrayList<>();
        Matcher m = CITATION.matcher(prose);
        while (m.find()) {
            found.add(Integer.parseInt(m.group(1)));
        }
        return found;
    }
}
