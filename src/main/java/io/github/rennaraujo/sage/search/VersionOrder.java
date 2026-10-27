package io.github.rennaraujo.sage.search;

import java.util.Comparator;
import java.util.Locale;

/**
 * Ordem semântica de versões: segmentos separados por ponto ou hífen, numéricos comparados como número ("4.10" depois
 * de "4.9", "25" depois de "21"). Qualificador não numérico (ea, rc1) é pré-lançamento e vem antes da versão final;
 * o resto cai para comparação de texto.
 * O desempate final por texto mantém o comparador consistente com equals (útil em {@code TreeSet}).
 */
final class VersionOrder implements Comparator<String> {

    static final VersionOrder INSTANCE = new VersionOrder();

    private static final String SEPARATORS = "[.\\-]";

    private VersionOrder() {}

    @Override
    public int compare(String a, String b) {
        String[] left = a.toLowerCase(Locale.ROOT).split(SEPARATORS);
        String[] right = b.toLowerCase(Locale.ROOT).split(SEPARATORS);
        int common = Math.min(left.length, right.length);
        for (int i = 0; i < common; i++) {
            int cmp = compareSegment(left[i], right[i]);
            if (cmp != 0) {
                return cmp;
            }
        }
        if (left.length != right.length) {
            // Sobra de segmentos: numérico ("4.0.1") faz a versão maior; qualificador ("25-ea", "4.0-rc1") a faz menor.
            boolean leftLonger = left.length > right.length;
            String extra = leftLonger ? left[common] : right[common];
            int sign = isNumeric(extra) ? 1 : -1;
            return leftLonger ? sign : -sign;
        }
        return a.compareTo(b);
    }

    private static int compareSegment(String a, String b) {
        boolean leftNum = isNumeric(a);
        boolean rightNum = isNumeric(b);
        if (leftNum && rightNum) {
            String x = stripLeadingZeros(a);
            String y = stripLeadingZeros(b);
            return x.length() != y.length() ? Integer.compare(x.length(), y.length()) : x.compareTo(y);
        }
        if (leftNum != rightNum) {
            // Qualificador (ea, rc1) é pré-lançamento: vem antes do segmento numérico na mesma posição.
            return leftNum ? 1 : -1;
        }
        return a.compareTo(b);
    }

    private static boolean isNumeric(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static String stripLeadingZeros(String s) {
        int i = 0;
        while (i < s.length() - 1 && s.charAt(i) == '0') {
            i++;
        }
        return s.substring(i);
    }
}
