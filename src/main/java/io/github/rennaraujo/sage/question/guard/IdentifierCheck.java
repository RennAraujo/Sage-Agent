package io.github.rennaraujo.sage.question.guard;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Nome de API ou de propriedade que a frase usa e nenhum trecho citado traz. O NLI não separa "Stream.toList()" de
 * "Stream.toImmutableList()": as duas frases parecem a mesma afirmação sobre listas. Um identificador inventado é a
 * alucinação mais comum em documentação técnica, e conferi-lo é lexical: toda referência de código da frase
 * ({@code Classe.metodo}, {@code metodo()}, {@code propriedade.com.pontos}, {@code @Anotacao}) precisa aparecer em pelo
 * menos um dos trechos citados. A comparação é feita depois de normalizar os dois lados: sem caixa, sem crases, com
 * {@code ::} lido como ponto e sem os parênteses de chamada vazios ou só com argumentos simples ({@code ()},
 * {@code (...)}, {@code (a, b)}), para "toList()" casar com "toList" e "List.of(...)" com "List.of()". Parênteses que
 * trazem outro identificador ({@code (see Stream.toList)}) ficam como estão. Frase sem identificadores passa por aqui
 * sem efeito.
 */
public final class IdentifierCheck {

    private static final Pattern IDENTIFIER = Pattern.compile("@[A-Za-z][A-Za-z0-9]+"
            + "|\\b[A-Za-z_][A-Za-z0-9_]+(?:(?:\\.|::)[A-Za-z_][A-Za-z0-9_-]*)+"
            + "|\\b[A-Za-z_][A-Za-z0-9_]+\\(\\s*\\)");
    /** Parênteses de chamada: vazios, reticências ou argumentos que são só palavras (sem ponto). */
    private static final Pattern CALL_ARGUMENTS = Pattern.compile("\\(\\s*(?:\\.{2,3}|\\w*(?:\\s*,\\s*\\w*)*)\\s*\\)");

    private IdentifierCheck() {}

    /** {@code true} se todo identificador da frase aparece em algum trecho (ou se a frase não tem identificadores). */
    public static boolean allKnown(String sentence, List<String> evidence) {
        List<String> normalizedEvidence =
                evidence.stream().map(IdentifierCheck::normalize).toList();
        Matcher matcher = IDENTIFIER.matcher(sentence);
        while (matcher.find()) {
            String identifier = normalize(matcher.group()).replaceAll("[.]+$", "");
            if (normalizedEvidence.stream().noneMatch(text -> text.contains(identifier))) {
                return false;
            }
        }
        return true;
    }

    private static String normalize(String text) {
        String plain = text.toLowerCase(Locale.ROOT).replace("`", "").replace("::", ".");
        return CALL_ARGUMENTS.matcher(plain).replaceAll("");
    }
}
