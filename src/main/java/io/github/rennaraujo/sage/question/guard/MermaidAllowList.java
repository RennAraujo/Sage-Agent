package io.github.rennaraujo.sage.question.guard;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Lista de permissão para blocos {@code mermaid}. A interface pode renderizar o diagrama, e o Mermaid tem diretivas
 * que executam ou carregam coisas ({@code click}, {@code %%{init}}, {@code style} com {@code url(...)}, entidades
 * {@code #lt;}), então negar o que se conhece é frágil. Aqui só passa o que se reconhece: um {@code flowchart} ou
 * {@code graph} com direção, e instruções (uma por linha ou separadas por {@code ;}) que sejam nó, aresta entre nós
 * ou {@code subgraph}/{@code end}. Nós têm id {@code [A-Za-z0-9_]+} e forma {@code [..]}, {@code (..)},
 * {@code ((..))} ou {@code {..}}; arestas são {@code -->}, {@code ---}, {@code -.->} ou {@code ==>}, com
 * {@code |rótulo|} opcional. Rótulo: letras, dígitos, espaço e pontuação simples, sem {@code < > & # % " `}, dois
 * pontos, parênteses ou colchetes. Qualquer outra coisa reprova o bloco inteiro.
 */
final class MermaidAllowList {

    private static final String LABEL = "[\\p{L}\\p{N} .,_/!?+*'-]+";
    private static final String NODE =
            "[A-Za-z0-9_]+(?:\\[" + LABEL + "]|\\(\\(" + LABEL + "\\)\\)|\\(" + LABEL + "\\)|\\{" + LABEL + "})?";
    private static final String EDGE = "\\s*(?:-->|---|-\\.->|==>)(?:\\|" + LABEL + "\\|)?\\s*";
    private static final Pattern HEADER = Pattern.compile("(?:flowchart|graph) (?:TD|TB|LR|RL|BT)");
    private static final Pattern STATEMENT = Pattern.compile(NODE + "(?:" + EDGE + NODE + ")*");
    private static final Pattern SUBGRAPH = Pattern.compile("subgraph [A-Za-z0-9_]+(?:\\[" + LABEL + "])?");
    private static final Pattern SHAPE_CONTENT = Pattern.compile("[\\[({][^\\])}]*[\\])}]");
    private static final Pattern EDGE_LABEL = Pattern.compile("\\|[^|]*\\|");
    private static final Pattern IDS = Pattern.compile("[A-Za-z0-9_]+");
    private static final Set<String> KEYWORDS = Set.of(
            "click",
            "call",
            "callback",
            "href",
            "style",
            "classdef",
            "class",
            "linkstyle",
            "link",
            "end",
            "subgraph",
            "default",
            "direction",
            "init",
            "accdescr",
            "acctitle",
            "graph",
            "flowchart");
    private static final String FENCE = "```";

    private MermaidAllowList() {}

    /** {@code block} é o bloco inteiro, com as cercas. */
    static boolean allowed(String block) {
        String[] lines = block.split("\n", -1);
        if (lines.length < 4
                || !(FENCE + "mermaid").equals(lines[0].strip())
                || !FENCE.equals(lines[lines.length - 1].strip())) {
            return false;
        }
        if (!HEADER.matcher(lines[1].strip()).matches()) {
            return false;
        }
        int statements = 0;
        for (int i = 2; i < lines.length - 1; i++) {
            for (String raw : lines[i].split(";", -1)) {
                String statement = raw.strip();
                if (statement.isEmpty()) {
                    continue;
                }
                if (!statementAllowed(statement)) {
                    return false;
                }
                statements++;
            }
        }
        return statements > 0;
    }

    private static boolean statementAllowed(String statement) {
        if ("end".equals(statement)) {
            return true;
        }
        if (SUBGRAPH.matcher(statement).matches()) {
            return true;
        }
        if (!STATEMENT.matcher(statement).matches()) {
            return false;
        }
        // Palavras reservadas do Mermaid não valem como id de nó ("click" sozinho, "A --> end").
        String withoutLabels = EDGE_LABEL
                .matcher(SHAPE_CONTENT.matcher(statement).replaceAll(""))
                .replaceAll("");
        var ids = IDS.matcher(withoutLabels);
        while (ids.find()) {
            if (KEYWORDS.contains(ids.group().toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }
}
