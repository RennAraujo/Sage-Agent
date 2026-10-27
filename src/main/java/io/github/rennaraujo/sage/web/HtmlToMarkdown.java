package io.github.rennaraujo.sage.web;

import io.github.rennaraujo.sage.shared.HtmlMarkdown;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Comment;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;

/**
 * Converte uma página da web em Markdown depois de arrancar tudo o que um leitor humano não veria. Esse texto escondido
 * é o canal clássico de injeção de prompt em páginas ({@code display:none} com "ignore as instruções anteriores"): o
 * modelo leria, a pessoa não. Sai o que é executável ou embutido (script, style, noscript, template, iframe, object,
 * embed), imagens e gráficos inteiros (o {@code alt} e o {@code title} do SVG são texto que ninguém lê na página), o
 * atributo {@code title} (tooltip), o que está marcado como oculto ({@code hidden}, {@code aria-hidden="true"}), os
 * elementos com classes de "só para leitor de tela" ({@code sr-only}, {@code visually-hidden}, {@code d-none}...), o
 * que o estilo inline esconde e os comentários HTML. Os comentários CSS são tirados do estilo antes de interpretá-lo,
 * para que {@code dis/**&#47;play:none} não engane o filtro. Caracteres Unicode de formatação (largura zero, bidi,
 * tags) saem no passo final, compartilhado com o upload ({@link HtmlMarkdown}).
 *
 * <p>Estilo inline considerado oculto: display none; visibility hidden/collapse; opacity até 0,01; font-size 0 ou até
 * 1px; color transparent; text-indent muito negativo; position absolute/fixed com deslocamento muito negativo; clip e
 * clip-path; altura ou largura zero com overflow hidden.
 *
 * <p>Risco residual: só o estilo inline e as classes acima são avaliados. Texto escondido por folha de estilo externa
 * ou por outra classe, e texto da mesma cor do fundo, não são detectados, porque isso exigiria renderizar a página.
 */
public final class HtmlToMarkdown {

    private static final String ALWAYS_REMOVED = "script,style,noscript,template,iframe,object,embed,frame,frameset,"
            + "img,picture,source,svg,math,canvas,audio,video,track,map,area";
    private static final Set<String> HIDING_CLASSES = Set.of(
            "sr-only", "visually-hidden", "visuallyhidden", "screen-reader-text", "d-none", "hidden", "invisible");
    private static final Pattern CSS_COMMENT = Pattern.compile("/\\*.*?(?:\\*/|$)", Pattern.DOTALL);
    private static final Pattern NUMBER = Pattern.compile("^([+-]?(?:\\d+\\.?\\d*|\\.\\d+))([a-z%]*)");
    /** Função de cor com argumentos: rgb(), rgba(), hsl(), hsla(). */
    private static final Pattern COLOR_FUNCTION = Pattern.compile("^(?:rgba?|hsla?)\\((.*)\\)$");
    /** Hexadecimal com alfa: 8 dígitos terminados em 00 ou 4 dígitos terminados em 0. */
    private static final Pattern TRANSPARENT_HEX = Pattern.compile("^#(?:[0-9a-f]{6}00|[0-9a-f]{3}0)$");

    private static final Pattern ZERO_SCALE = Pattern.compile("scale(?:x|y|3d)?\\(\\s*0(?:\\.0+)?\\s*[,)]");
    private static final int ALPHA_ARGUMENT_COUNT = 4;
    private static final double OPACITY_INVISIBLE = 0.01;
    private static final double TINY_PIXELS = 1.0;
    private static final double OFFSCREEN = -100.0;
    private static final Set<String> OFFSET_PROPERTIES =
            Set.of("left", "top", "right", "bottom", "margin-left", "margin-top");
    private static final Set<String> ZERO_SIZE_PROPERTIES = Set.of("height", "max-height", "width", "max-width");
    private static final Set<String> OVERFLOW_PROPERTIES = Set.of("overflow", "overflow-x", "overflow-y");

    public String convert(String html) {
        Document doc = Jsoup.parse(HtmlMarkdown.stripFormatCharacters(html));
        doc.select(ALWAYS_REMOVED).remove();
        removeHidden(doc);
        doc.getAllElements().forEach(e -> e.removeAttr("title"));
        removeComments(doc);
        return HtmlMarkdown.fromHtml(doc.html()).strip();
    }

    private static void removeHidden(Document doc) {
        List<Element> hidden = new ArrayList<>();
        for (Element element : doc.getAllElements()) {
            if (element.hasAttr("hidden")
                    || "true".equalsIgnoreCase(element.attr("aria-hidden").strip())
                    || element.classNames().stream().anyMatch(c -> HIDING_CLASSES.contains(c.toLowerCase(Locale.ROOT)))
                    || hiddenByStyle(element.attr("style"))) {
                hidden.add(element);
            }
        }
        hidden.forEach(Element::remove);
    }

    private static void removeComments(Node root) {
        List<Node> comments = new ArrayList<>();
        collectComments(root, comments);
        comments.forEach(Node::remove);
    }

    private static void collectComments(Node node, List<Node> out) {
        for (Node child : node.childNodes()) {
            if (child instanceof Comment) {
                out.add(child);
            } else {
                collectComments(child, out);
            }
        }
    }

    private static boolean hiddenByStyle(String style) {
        if (style.isBlank()) {
            return false;
        }
        Map<String, String> css = declarations(CSS_COMMENT.matcher(style).replaceAll(""));
        String position = css.getOrDefault("position", "");
        boolean positioned = position.equals("absolute") || position.equals("fixed");
        boolean clipsOverflow = css.entrySet().stream()
                .anyMatch(e ->
                        OVERFLOW_PROPERTIES.contains(e.getKey()) && e.getValue().contains("hidden"));
        for (Map.Entry<String, String> declaration : css.entrySet()) {
            String property = declaration.getKey();
            String value = declaration.getValue();
            boolean hides = switch (property) {
                case "display" -> value.equals("none");
                case "visibility" -> value.equals("hidden") || value.equals("collapse");
                case "opacity" -> number(value).stream().anyMatch(n -> n <= OPACITY_INVISIBLE);
                case "font-size" -> tinyFont(value);
                case "color" -> value.equals("transparent") || fullyTransparent(value);
                case "transform" -> ZERO_SCALE.matcher(value).find();
                case "text-indent" -> number(value).stream().anyMatch(n -> n <= OFFSCREEN);
                case "clip" -> !value.isEmpty() && !value.equals("auto");
                case "clip-path" -> !value.isEmpty() && !value.equals("none");
                default ->
                    (positioned
                                    && OFFSET_PROPERTIES.contains(property)
                                    && number(value).stream().anyMatch(n -> n <= OFFSCREEN))
                            || (clipsOverflow
                                    && ZERO_SIZE_PROPERTIES.contains(property)
                                    && number(value).stream().anyMatch(n -> n == 0.0));
            };
            if (hides) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, String> declarations(String style) {
        Map<String, String> out = new HashMap<>();
        for (String declaration : style.split(";")) {
            int colon = declaration.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String property = declaration.substring(0, colon).strip().toLowerCase(Locale.ROOT);
            String value = declaration
                    .substring(colon + 1)
                    .replace("!important", "")
                    .strip()
                    .toLowerCase(Locale.ROOT);
            out.merge(property, value, (previous, next) -> previous + " " + next);
        }
        return out;
    }

    /**
     * Cor totalmente transparente. O alfa só existe como quarto argumento (forma com vírgulas) ou depois da barra
     * (forma moderna); {@code rgb(0,0,0)} e {@code hsl(120,100%,0%)} têm três componentes e são opacos.
     */
    private static boolean fullyTransparent(String value) {
        if (TRANSPARENT_HEX.matcher(value).matches()) {
            return true;
        }
        Matcher function = COLOR_FUNCTION.matcher(value);
        if (!function.matches()) {
            return false;
        }
        String args = function.group(1);
        String alpha;
        int slash = args.indexOf('/');
        if (slash >= 0) {
            alpha = args.substring(slash + 1);
        } else {
            String[] parts = args.split(",");
            if (parts.length != ALPHA_ARGUMENT_COUNT) {
                return false;
            }
            alpha = parts[ALPHA_ARGUMENT_COUNT - 1];
        }
        return number(alpha).stream().anyMatch(n -> n == 0.0);
    }

    private static boolean tinyFont(String value) {
        Matcher m = NUMBER.matcher(value);
        if (!m.find()) {
            return false;
        }
        double size = Double.parseDouble(m.group(1));
        return size == 0.0 || (m.group(2).equals("px") && size <= TINY_PIXELS);
    }

    private static OptionalDouble number(String value) {
        Matcher m = NUMBER.matcher(value.strip());
        return m.find() ? OptionalDouble.of(Double.parseDouble(m.group(1))) : OptionalDouble.empty();
    }
}
