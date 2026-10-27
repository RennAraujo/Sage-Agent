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
    private static final Pattern INLINE_CODE = Pattern.compile("`[^`]*`");

    private final int contextSize;

    CitationValidator(int contextSize) {
        this.contextSize = contextSize;
    }

    boolean valid(String sentence) {
        List<Integer> cited = citations(sentence);
        return !cited.isEmpty() && cited.stream().allMatch(n -> n >= 1 && n <= contextSize);
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
