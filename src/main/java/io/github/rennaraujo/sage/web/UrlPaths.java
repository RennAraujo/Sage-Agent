package io.github.rennaraujo.sage.web;

import java.util.regex.Pattern;

/**
 * Checagens de caminho de URL usadas tanto pela importação quanto pelo fallback web. Ponto ou barra codificados ({@code
 * %2e}, {@code %2f}, e a contrabarra {@code %5c}) não são resolvidos pelo {@code URI.normalize()}, mas o servidor pode
 * decodificá-los e resolver o caminho: {@code /javase/21/%2e%2e/25/x} passa por página do 21 e é servida como do 25.
 * Qualquer um deles derruba a URL.
 */
final class UrlPaths {

    private static final Pattern ENCODED_DOT_OR_SLASH = Pattern.compile("(?i)%2e|%2f|%5c");

    private UrlPaths() {}

    /** O caminho cru (antes de decodificar) traz ponto, barra ou contrabarra codificados. */
    static boolean hasEncodedDotOrSlash(String rawPath) {
        return rawPath != null && ENCODED_DOT_OR_SLASH.matcher(rawPath).find();
    }

    /** Sobrou algum segmento {@code .} ou {@code ..} (o {@code normalize()} mantém {@code /../x} no começo). */
    static boolean hasDotSegment(String path) {
        if (path == null) {
            return false;
        }
        for (String segment : path.split("/", -1)) {
            if (".".equals(segment) || "..".equals(segment)) {
                return true;
            }
        }
        return false;
    }
}
