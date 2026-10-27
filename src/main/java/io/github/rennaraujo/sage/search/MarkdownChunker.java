package io.github.rennaraujo.sage.search;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Divide Markdown em trechos por título. Cada trecho guarda o caminho de títulos ("Streams > toList"). Dentro de uma
 * seção, parágrafos e blocos de código são agrupados até o limite de caracteres. Bloco de código nunca é cortado (um
 * bloco maior que o limite vira um trecho sozinho); parágrafo maior que o limite é dividido em fim de frase ou espaço.
 * Marcadores de título dentro de bloco de código não contam como título.
 */
final class MarkdownChunker {

    /** Pedaço de documento: caminho de títulos (vazio antes do primeiro título) e o texto. */
    record Section(String headingPath, String text) {}

    private static final Pattern HEADING = Pattern.compile("^ {0,3}(#{1,6})[ \\t]+(.*?)(?:[ \\t]+#+)?[ \\t]*$");
    private static final Pattern SETEXT = Pattern.compile("^ {0,3}(=+|-+)[ \\t]*$");
    private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,})");
    private static final String PATH_SEPARATOR = " > ";
    private static final int MAX_HEADING_LEVEL = 6;
    private static final String BLOCK_SEPARATOR = "\n\n";

    private final int maxChars;

    MarkdownChunker(int maxChars) {
        if (maxChars < 1) {
            throw new IllegalArgumentException("maxChars deve ser positivo");
        }
        this.maxChars = maxChars;
    }

    List<Section> split(String markdown) {
        List<Section> out = new ArrayList<>();
        String[] headings = new String[MAX_HEADING_LEVEL + 1];
        List<Block> blocks = new ArrayList<>();
        StringBuilder paragraph = new StringBuilder();
        String fence = null;
        StringBuilder code = new StringBuilder();

        for (String line : markdown.split("\n", -1)) {
            if (fence != null) {
                code.append('\n').append(line);
                if (closesFence(line, fence)) {
                    blocks.add(new Block(code.toString(), true));
                    code.setLength(0);
                    fence = null;
                }
                continue;
            }
            Matcher fenceMatch = FENCE.matcher(line);
            if (fenceMatch.find()) {
                flushParagraph(paragraph, blocks);
                fence = fenceMatch.group(1);
                code.append(line);
                continue;
            }
            Matcher heading = HEADING.matcher(line);
            Matcher setext = SETEXT.matcher(line);
            if (heading.matches()) {
                flushParagraph(paragraph, blocks);
                startSection(
                        headings, heading.group(1).length(), heading.group(2).strip(), blocks, out);
            } else if (setext.matches() && !paragraph.isEmpty() && paragraph.indexOf("\n") < 0) {
                // "Título" seguido de ==== (nível 1) ou ---- (nível 2): o parágrafo de uma linha vira o título.
                String title = paragraph.toString().strip();
                paragraph.setLength(0);
                startSection(headings, setext.group(1).charAt(0) == '=' ? 1 : 2, title, blocks, out);
            } else if (line.isBlank()) {
                flushParagraph(paragraph, blocks);
            } else {
                if (!paragraph.isEmpty()) {
                    paragraph.append('\n');
                }
                paragraph.append(line.stripTrailing());
            }
        }
        if (fence != null) {
            blocks.add(new Block(code.toString(), true));
        }
        flushParagraph(paragraph, blocks);
        pack(path(headings), blocks, out);
        return out;
    }

    private void startSection(String[] headings, int level, String title, List<Block> blocks, List<Section> out) {
        pack(path(headings), blocks, out);
        blocks.clear();
        headings[level] = title;
        for (int deeper = level + 1; deeper <= MAX_HEADING_LEVEL; deeper++) {
            headings[deeper] = null;
        }
    }

    private static boolean closesFence(String line, String fence) {
        String stripped = line.strip();
        return stripped.length() >= fence.length()
                && stripped.chars().allMatch(c -> c == fence.charAt(0))
                && line.indexOf(fence.charAt(0)) <= 3;
    }

    private static String path(String[] headings) {
        List<String> parts = new ArrayList<>();
        for (int level = 1; level < headings.length; level++) {
            if (headings[level] != null && !headings[level].isEmpty()) {
                parts.add(headings[level]);
            }
        }
        return String.join(PATH_SEPARATOR, parts);
    }

    private static void flushParagraph(StringBuilder paragraph, List<Block> blocks) {
        if (!paragraph.isEmpty()) {
            blocks.add(new Block(paragraph.toString(), false));
            paragraph.setLength(0);
        }
    }

    private void pack(String headingPath, List<Block> blocks, List<Section> out) {
        StringBuilder current = new StringBuilder();
        for (Block block : blocks) {
            if (block.text().length() > maxChars) {
                emit(headingPath, current, out);
                if (block.code()) {
                    out.add(new Section(headingPath, block.text()));
                } else {
                    for (String piece : splitLongText(block.text())) {
                        out.add(new Section(headingPath, piece));
                    }
                }
                continue;
            }
            int needed = current.isEmpty()
                    ? block.text().length()
                    : current.length() + 2 + block.text().length();
            if (needed > maxChars) {
                emit(headingPath, current, out);
            }
            if (!current.isEmpty()) {
                current.append(BLOCK_SEPARATOR);
            }
            current.append(block.text());
        }
        emit(headingPath, current, out);
    }

    private static void emit(String headingPath, StringBuilder current, List<Section> out) {
        if (!current.isEmpty()) {
            out.add(new Section(headingPath, current.toString()));
            current.setLength(0);
        }
    }

    /** Corta em fim de frase, senão em espaço, senão no limite (sem partir par substituto). */
    private List<String> splitLongText(String text) {
        List<String> pieces = new ArrayList<>();
        String rest = text;
        while (rest.length() > maxChars) {
            int cut = cutPoint(rest);
            String piece = rest.substring(0, cut).strip();
            if (!piece.isEmpty()) {
                pieces.add(piece);
            }
            rest = rest.substring(cut).stripLeading();
        }
        if (!rest.isBlank()) {
            pieces.add(rest.strip());
        }
        return pieces;
    }

    private int cutPoint(String text) {
        int window = Math.min(text.length(), maxChars);
        int sentence = Math.max(text.lastIndexOf(". ", window - 1), text.lastIndexOf('\n', window - 1));
        if (sentence > 0) {
            return sentence + 1;
        }
        int space = text.lastIndexOf(' ', window - 1);
        if (space > 0) {
            return space;
        }
        if (Character.isHighSurrogate(text.charAt(window - 1)) && window > 1) {
            return window - 1;
        }
        return window;
    }

    private record Block(String text, boolean code) {}
}
