package io.github.rennaraujo.sage.question.guard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Divide um trecho citado em janelas de {@value #WINDOW} frases, com passo {@value #STEP}, para o NLI
 * conferir a frase da resposta contra cada janela e ficar com a maior probabilidade. Um modelo de NLI
 * vê entailment quando a premissa é curta; com o trecho inteiro (até 1500 caracteres) a probabilidade
 * de uma frase que está literalmente nele caía a 0,2 a 0,6. Janelas de 2 frases foram o melhor
 * compromisso medido na Task 20 (janelas de 1 frase aceitam mais afirmações erradas, de 3 se parecem
 * com o trecho inteiro). Com mais de {@value #MAX_PER_PREMISE} candidatas por trecho, ficam as que
 * mais dividem palavras com a frase, o que também limita o custo. A primeira linha, quando parece um
 * título de seção (curta e sem pontuação final), vai na frente de cada janela, porque ela dá o
 * assunto.
 */
public final class PremiseWindows {

    public static final int WINDOW = 2;
    public static final int STEP = 1;
    /** Teto de janelas por trecho. Passou disso, ficam as mais parecidas com a frase (palavras em comum), em ordem. */
    public static final int MAX_PER_PREMISE = 4;

    private static final int MAX_HEADING_CHARS = 120;
    private static final Pattern SENTENCE_BREAK = Pattern.compile("(?<=[.!?])\\s+(?=\\S)|\\s*\\n+\\s*");
    private static final Pattern ENDS_LIKE_A_SENTENCE = Pattern.compile("[.!?:;]\\s*$");

    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}_]{3,}");

    private static final Pattern NOT_ALNUM = Pattern.compile("[^\\p{L}\\p{N}]+");
    /** Abaixo disso um trecho "contido" no outro não prova nada (uma palavra solta está em qualquer trecho). */
    private static final int MIN_COPY_CHARS = 20;

    private PremiseWindows() {}

    /** Cobertura mínima de uma frase do trecho para a cópia valer quando ela não a traz inteira. */
    private static final double COVERAGE = 0.9;

    /** Palavras que invertem ou restringem uma afirmação; cortá-las de uma cópia muda o que a frase diz. */
    private static final Set<String> POLARITY = Set.of(
            "not", "no", "never", "nor", "none", "without", "cannot", "only", "unless", "except", "nunca", "não", "nao",
            "nem", "sem", "apenas", "somente", "só", "exceto", "jamais");

    /** Teto de pares (janelas) que o NLI pontua por frase, somados todos os trechos citados. */
    public static final int MAX_PAIRS = 8;

    /**
     * A frase é uma cópia do trecho (sem contar caixa, pontuação e espaços)? Copiar o trecho não pode ser alucinação, e
     * o NLI erra justamente aí: premissa e hipótese quase iguais, num trecho longo, às vezes dão 0,15 de entailment.
     * Mas só vale cópia de frase completa: um pedaço que começa depois de "não" ou termina antes de "quando..." diz
     * outra coisa. Conta quando a cópia traz ao menos uma frase inteira do trecho, ou 90% dela sem ter cortado palavra
     * de negação ou restrição. Frases curtas demais não contam.
     */
    public static boolean copiedFrom(String premise, String sentence) {
        String wanted = normalized(sentence);
        if (wanted.length() < MIN_COPY_CHARS || !normalized(premise).contains(wanted)) {
            return false;
        }
        for (String candidate : sentencesOf(premise)) {
            String full = normalized(candidate);
            if (full.isEmpty()) {
                continue;
            }
            if (wanted.contains(full)) {
                return true;
            }
            if (full.contains(wanted) && wanted.length() >= COVERAGE * full.length() && !dropsPolarity(full, wanted)) {
                return true;
            }
        }
        return false;
    }

    private static boolean dropsPolarity(String full, String wanted) {
        String dropped = full.replace(wanted, " ");
        for (String word : dropped.split(" ")) {
            if (POLARITY.contains(word)) {
                return true;
            }
        }
        return false;
    }

    /** Até {@code max} janelas, as que mais dividem palavras com a frase, na ordem em que vieram. */
    public static List<String> best(List<String> windows, String hypothesis, int max) {
        if (windows.size() <= max) {
            return windows;
        }
        Set<String> wanted = tokens(hypothesis);
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < windows.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator.comparingInt((Integer i) -> -overlap(wanted, windows.get(i)))
                .thenComparingInt(i -> i));
        List<Integer> kept = new ArrayList<>(order.subList(0, max));
        Collections.sort(kept);
        return kept.stream().map(windows::get).toList();
    }

    private static List<String> sentencesOf(String premise) {
        String body = premise;
        int newline = premise.indexOf('\n');
        if (newline > 0 && looksLikeHeading(premise.substring(0, newline))) {
            body = premise.substring(newline + 1);
        }
        List<String> sentences = new ArrayList<>();
        for (String part : SENTENCE_BREAK.split(body)) {
            if (!part.isBlank()) {
                sentences.add(part.strip());
            }
        }
        return sentences;
    }

    private static boolean looksLikeHeading(String line) {
        String first = line.strip();
        return first.length() <= MAX_HEADING_CHARS
                && !ENDS_LIKE_A_SENTENCE.matcher(first).find();
    }

    private static String normalized(String text) {
        return NOT_ALNUM.matcher(text.toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
    }

    private static Set<String> tokens(String text) {
        Set<String> found = new HashSet<>();
        Matcher matcher = TOKEN.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            found.add(matcher.group());
        }
        return found;
    }

    private static int overlap(Set<String> wanted, String sentence) {
        Set<String> present = tokens(sentence);
        int hits = 0;
        for (String token : wanted) {
            if (present.contains(token)) {
                hits++;
            }
        }
        return hits;
    }

    /** As janelas do trecho; um trecho de uma frase só (ou sem frases) sai inteiro, numa janela só. */
    public static List<String> of(String premise, String hypothesis) {
        return of(premise, hypothesis, WINDOW);
    }

    /** Como {@link #of(String, String)}, com janelas de {@code window} frases (passo de uma frase). */
    public static List<String> of(String premise, String hypothesis, int window) {
        String heading = "";
        String body = premise;
        int newline = premise.indexOf('\n');
        if (newline > 0) {
            String first = premise.substring(0, newline).strip();
            if (first.length() <= MAX_HEADING_CHARS
                    && !ENDS_LIKE_A_SENTENCE.matcher(first).find()) {
                heading = first + "\n";
                body = premise.substring(newline + 1);
            }
        }
        List<String> sentences = new ArrayList<>();
        for (String part : SENTENCE_BREAK.split(body)) {
            if (!part.isBlank()) {
                sentences.add(part.strip());
            }
        }
        if (sentences.size() <= window) {
            return List.of(premise);
        }
        List<Integer> starts = new ArrayList<>();
        for (int start = 0; start + window <= sentences.size(); start += STEP) {
            starts.add(start);
        }
        if (starts.size() > MAX_PER_PREMISE) {
            Set<String> wanted = tokens(hypothesis);
            // Mais parecidas com a frase primeiro; empate fica com a que vem antes no trecho.
            starts.sort(Comparator.comparingInt((Integer i) ->
                            -overlap(wanted, String.join(" ", sentences.subList(i, i + window))))
                    .thenComparingInt(i -> i));
            starts = new ArrayList<>(starts.subList(0, MAX_PER_PREMISE));
            Collections.sort(starts);
        }
        List<String> windows = new ArrayList<>();
        for (int start : starts) {
            windows.add(heading + String.join(" ", sentences.subList(start, start + window)));
        }
        return windows;
    }
}
