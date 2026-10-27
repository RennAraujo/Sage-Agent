package io.github.rennaraujo.sage.question.guard;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Corta o texto do modelo, que chega em pedaços arbitrários, em frases e blocos de código completos.
 *
 * <p>Uma frase termina numa quebra de linha ou em {@code .}, {@code !} ou {@code ?} seguido de espaço e de algo que
 * começa frase (maiúscula, dígito, crase, colchete), para abreviações não partirem a frase. Se depois do
 * espaço vier uma citação ({@code "Use toList(). [1]"}), ela fica com a frase. Para isso o splitter às vezes espera o
 * próximo pedaço antes de soltar a frase: custa um token de latência e evita descartar uma frase bem citada. Um bloco
 * de código começa com uma linha que abre com três crases e vai até a linha que as fecha; sai inteiro, com as cercas.
 */
final class SentenceSplitter {

    /**
     * Uma frase ou um bloco de código (com as cercas), sem espaço nas pontas. {@code closed} só é falso num bloco de
     * código que o stream terminou sem fechar.
     */
    record Segment(String text, boolean code, boolean closed) {}

    private static final String FENCE = "```";
    private static final Pattern CITATION_AHEAD = Pattern.compile("\\[\\d+]");
    private static final Pattern PARTIAL_CITATION = Pattern.compile("\\[\\d*");
    private static final int MAX_LIST_MARKER_DIGITS = 3;
    private static final int WAIT = -1;
    private static final int NOT_A_BOUNDARY = -2;

    private final StringBuilder buffer = new StringBuilder();
    private boolean inCode;
    private boolean blockClosed;

    List<Segment> push(String delta) {
        buffer.append(delta);
        return drain(false);
    }

    /** Fim do stream: o que sobrou vira o último segmento (bloco sem cerca de fechamento sai marcado como aberto). */
    List<Segment> flush() {
        return drain(true);
    }

    private List<Segment> drain(boolean last) {
        List<Segment> out = new ArrayList<>();
        while (true) {
            if (inCode) {
                int end = codeBlockEnd(last);
                if (end < 0) {
                    return out;
                }
                emit(out, end, true, blockClosed);
                inCode = false;
                continue;
            }
            skipLeadingWhitespace();
            if (buffer.isEmpty()) {
                return out;
            }
            if (startsWithFence()) {
                inCode = true;
                continue;
            }
            if (!last && buffer.length() < FENCE.length() && isAllBackticks()) {
                return out;
            }
            int end = sentenceEnd(last);
            if (end < 0) {
                return out;
            }
            emit(out, end, false, true);
        }
    }

    private void emit(List<Segment> out, int end, boolean code, boolean closed) {
        String text = buffer.substring(0, end).strip();
        buffer.delete(0, end);
        if (!text.isEmpty()) {
            out.add(new Segment(text, code, closed));
        }
    }

    /**
     * Fim do bloco de código aberto no início do buffer, ou -1 se ele ainda não fechou. No fim do stream, um bloco sem
     * cerca de fechamento vai até o fim do buffer e é marcado como aberto ({@link #blockClosed} falso).
     */
    private int codeBlockEnd(boolean last) {
        int firstLineEnd = buffer.indexOf("\n");
        int closing = firstLineEnd < 0 ? -1 : buffer.indexOf("\n" + FENCE, firstLineEnd);
        blockClosed = closing >= 0;
        if (closing < 0) {
            return last ? buffer.length() : -1;
        }
        int lineEnd = buffer.indexOf("\n", closing + 1);
        if (lineEnd >= 0) {
            return lineEnd;
        }
        return last ? buffer.length() : -1;
    }

    /** Fim da frase no início do buffer, ou -1 se ainda não dá para saber. */
    private int sentenceEnd(boolean last) {
        for (int i = 0; i < buffer.length(); i++) {
            char c = buffer.charAt(i);
            if (c == '\n') {
                return i;
            }
            if (!isTerminator(c) || isListMarker(i)) {
                continue;
            }
            if (i + 1 == buffer.length()) {
                return last ? buffer.length() : -1;
            }
            if (Character.isWhitespace(buffer.charAt(i + 1))) {
                int end = boundaryAfter(i + 1, last);
                if (end == WAIT) {
                    return -1;
                }
                if (end != NOT_A_BOUNDARY) {
                    return end;
                }
            }
        }
        return last ? buffer.length() : -1;
    }

    /**
     * Depois de um terminador seguido de espaço, decide se a frase acabou ali. Citações que vierem em seguida ficam com
     * a frase e encerram a decisão. Sem citação, só é fronteira se o próximo caractere visível começa uma frase nova
     * (maiúscula, dígito, crase, colchete ou quebra de linha): "e.g. toList()", "p. ex. collect()" e "aprox. dez" não
     * partem a frase. Devolve o fim da frase, {@link #WAIT} se o texto acabou antes de dar para decidir, ou
     * {@link #NOT_A_BOUNDARY}.
     */
    private int boundaryAfter(int from, boolean last) {
        int end = from;
        int j = skipSpaces(from);
        boolean cited = false;
        while (j < buffer.length()) {
            Matcher full = CITATION_AHEAD.matcher(buffer);
            full.region(j, buffer.length());
            if (full.lookingAt()) {
                end = full.end();
                cited = true;
                j = skipSpaces(end);
                continue;
            }
            Matcher partial = PARTIAL_CITATION.matcher(buffer);
            partial.region(j, buffer.length());
            if (!last && partial.lookingAt() && partial.end() == buffer.length()) {
                return WAIT;
            }
            break;
        }
        if (cited) {
            return end;
        }
        if (j == buffer.length()) {
            return last ? end : WAIT;
        }
        return startsSentence(buffer.charAt(j)) ? end : NOT_A_BOUNDARY;
    }

    private static boolean startsSentence(char c) {
        return c == '\n' || c == '`' || c == '[' || Character.isUpperCase(c) || Character.isDigit(c);
    }

    /** Pula espaços e tabs, mas não quebra de linha: citação na linha seguinte não é desta frase. */
    private int skipSpaces(int from) {
        int j = from;
        while (j < buffer.length() && Character.isWhitespace(buffer.charAt(j)) && buffer.charAt(j) != '\n') {
            j++;
        }
        return j;
    }

    private void skipLeadingWhitespace() {
        int i = 0;
        while (i < buffer.length() && Character.isWhitespace(buffer.charAt(i))) {
            i++;
        }
        buffer.delete(0, i);
    }

    private boolean startsWithFence() {
        return buffer.length() >= FENCE.length() && FENCE.contentEquals(buffer.subSequence(0, FENCE.length()));
    }

    private boolean isAllBackticks() {
        for (int i = 0; i < buffer.length(); i++) {
            if (buffer.charAt(i) != '`') {
                return false;
            }
        }
        return true;
    }

    /**
     * O ponto de "1. Receba o pedido" é a numeração do item, não o fim de uma frase: só dígitos (até três) entre o
     * começo da linha e o ponto. Sem isso o "1." viraria uma frase sem citação, descartada, e levaria a numeração.
     */
    private boolean isListMarker(int dot) {
        if (buffer.charAt(dot) != '.') {
            return false;
        }
        int start = dot;
        while (start > 0 && buffer.charAt(start - 1) != '\n') {
            start--;
        }
        int digits = dot - start;
        if (digits < 1 || digits > MAX_LIST_MARKER_DIGITS) {
            return false;
        }
        for (int k = start; k < dot; k++) {
            if (!Character.isDigit(buffer.charAt(k))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isTerminator(char c) {
        return c == '.' || c == '!' || c == '?';
    }
}
