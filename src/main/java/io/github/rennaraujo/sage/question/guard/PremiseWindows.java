package io.github.rennaraujo.sage.question.guard;

import java.text.Normalizer;
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
 * Divide um trecho citado em janelas de {@value #WINDOW} frases, com passo {@value #STEP}, para o NLI conferir a frase
 * da resposta contra cada janela e ficar com a maior probabilidade. Um modelo de NLI vê entailment quando a premissa é
 * curta; com o trecho inteiro (até 1500 caracteres) a probabilidade de uma frase que está literalmente nele caía a 0,2
 * a 0,6. Janelas de 2 frases foram o melhor compromisso medido na calibração descrita em {@code docs/decisoes.md}
 * (janelas de 1 frase aceitam mais afirmações erradas, de 3 se parecem com o trecho inteiro). Com mais de {@value
 * #MAX_PER_PREMISE} candidatas por trecho, ficam as que mais dividem palavras com a frase, o que também limita o custo.
 * A primeira linha, quando parece um título de seção (curta e sem pontuação final), vai na frente de cada janela,
 * porque ela dá o assunto.
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

    /** Marcador de item de lista no começo da linha ("- ", "* ", "+ ", "1. ", "2) "). */
    private static final Pattern LIST_ITEM = Pattern.compile("^\\s*(?:[-*+]|\\d+[.)])\\s+");

    private static final Pattern WITHIN_LINE = Pattern.compile("(?<=[.!?])\\s+(?=\\S)");

    /**
     * Linha que restringe o que vem embaixo dela: traz número (versão) ou palavra de condição. Roda sobre o texto sem
     * acento e em minúsculas.
     */
    private static final Pattern VERSION_OR_CONDITION = Pattern.compile("\\d|\\b(?:ate|antes|desde|apos|depois|a partir"
            + "|quando|se|somente|apenas|exceto|salvo|enquanto|until|before|since|after|from|when|if|only|unless"
            + "|except|prior)\\b");

    /** Teto de pares (janelas) que o NLI pontua por frase, somados todos os trechos citados. */
    public static final int MAX_PAIRS = 8;

    /**
     * Uma frase do trecho, com o que a cerca: se é item de lista, a linha de que ela depende (para item de lista, a
     * linha não-item mais próxima acima) e se ocupa a linha inteira.
     */
    private record Piece(String raw, boolean listItem, boolean wholeLine, String parent) {}

    /**
     * A frase é uma cópia do trecho (sem contar caixa, pontuação e espaços)? Copiar o trecho não pode ser alucinação, e
     * o NLI erra justamente aí: premissa e hipótese quase iguais, num trecho longo, às vezes dão 0,15 de entailment.
     *
     * <p>Só vale cópia exata de uma ou mais frases inteiras e seguidas do trecho, começando e terminando em fronteira
     * de frase: um pedaço que começa depois de "Antes do Java 24," ou termina antes de "quando..." diz outra coisa. E
     * mesmo a cópia exata cai para o NLI quando o contexto dela restringe o que ela diz: a frase anterior termina em
     * ":" ou é um título ou uma linha de introdução de lista ("Até o Java 23:"), ou a cópia inclui um item de lista
     * cuja linha de cima nomeia versão ou condição. Frases curtas demais não contam.
     */
    public static boolean copiedFrom(String premise, String sentence) {
        String wanted = normalized(sentence);
        if (wanted.length() < MIN_COPY_CHARS || !normalized(premise).contains(wanted)) {
            return false;
        }
        int newline = premise.indexOf('\n');
        if (newline > 0
                && looksLikeHeading(premise.substring(0, newline))
                && VERSION_OR_CONDITION
                        .matcher(folded(premise.substring(0, newline)))
                        .find()) {
            // O assunto do trecho ("GC > Java 17 e anteriores") restringe tudo o que vem embaixo: vale o NLI, que vê
            // o título junto de cada janela.
            return false;
        }
        List<Piece> pieces = piecesOf(premise);
        for (int first = 0; first < pieces.size(); first++) {
            StringBuilder span = new StringBuilder();
            for (int last = first; last < pieces.size(); last++) {
                span.append(' ').append(pieces.get(last).raw());
                String copy = normalized(span.toString());
                if (copy.length() > wanted.length()) {
                    break;
                }
                if (copy.equals(wanted)) {
                    return !restricted(pieces, first, last);
                }
            }
        }
        return false;
    }

    /** O contexto da cópia {@code [first, last]} restringe o que ela diz? */
    private static boolean restricted(List<Piece> pieces, int first, int last) {
        if (first > 0) {
            Piece before = pieces.get(first - 1);
            String text = before.raw().strip();
            boolean heading = before.wholeLine() && !before.listItem() && looksLikeHeading(text);
            if (text.endsWith(":") || text.startsWith("#") || heading) {
                return true;
            }
        }
        for (int i = first; i <= last; i++) {
            Piece piece = pieces.get(i);
            if (piece.listItem()
                    && VERSION_OR_CONDITION.matcher(folded(piece.parent())).find()) {
                return true;
            }
        }
        return false;
    }

    /** As frases do trecho, linha a linha; a primeira linha com cara de título fica de fora (é o assunto). */
    private static List<Piece> piecesOf(String premise) {
        String parent = "";
        String body = premise;
        int newline = premise.indexOf('\n');
        if (newline > 0 && looksLikeHeading(premise.substring(0, newline))) {
            parent = premise.substring(0, newline).strip();
            body = premise.substring(newline + 1);
        }
        List<Piece> pieces = new ArrayList<>();
        for (String line : body.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            Matcher marker = LIST_ITEM.matcher(line);
            boolean listItem = marker.find();
            String text = listItem ? line.substring(marker.end()) : line;
            List<String> parts = new ArrayList<>();
            for (String part : WITHIN_LINE.split(text.strip())) {
                if (!part.isBlank()) {
                    parts.add(part.strip());
                }
            }
            for (String part : parts) {
                pieces.add(new Piece(part, listItem, parts.size() == 1, parent));
            }
            if (!listItem) {
                parent = line.strip();
            }
        }
        return pieces;
    }

    private static String folded(String text) {
        return Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
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
