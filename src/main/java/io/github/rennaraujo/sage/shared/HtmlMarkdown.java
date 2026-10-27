package io.github.rennaraujo.sage.shared;

import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter;
import com.vladsch.flexmark.util.data.MutableDataSet;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;

/**
 * Conversão HTML para Markdown compartilhada pelo upload e pelo fallback web: primeiro o jsoup limpa com
 * {@link Safelist#relaxed()} (sem script, sem atributos de evento, sem URLs perigosas) e depois o flexmark converte.
 * Quem chama decide o que remover antes, como texto escondido de uma página da web.
 */
public final class HtmlMarkdown {

    private HtmlMarkdown() {}

    public static String fromHtml(String html) {
        String clean = Jsoup.clean(html, Safelist.relaxed());
        // Títulos com # (ATX): é o que o chunking por seção procura mais adiante, e o padrão do flexmark seria
        // sublinhado.
        MutableDataSet options = new MutableDataSet().set(FlexmarkHtmlConverter.SETEXT_HEADINGS, false);
        return stripFormatCharacters(
                FlexmarkHtmlConverter.builder(options).build().convert(clean));
    }

    /**
     * Remove caracteres de formatação Unicode (categoria Cf): espaços de largura zero, marcas de direção e controles
     * bidirecionais, caracteres de tag (U+E0000..U+E007F), BOM. Não aparecem na tela, mas o modelo os lê e eles
     * escondem texto (por exemplo, instruções codificadas em caracteres de tag). Efeito colateral aceito: o ZWJ de
     * emojis compostos e o ZWNJ de alguns alfabetos (persa, indianos) também saem; em documentação técnica em
     * português ou inglês isso não muda o sentido.
     */
    public static String stripFormatCharacters(String text) {
        StringBuilder out = new StringBuilder(text.length());
        text.codePoints()
                .filter(cp -> Character.getType(cp) != Character.FORMAT)
                .forEach(out::appendCodePoint);
        return out.toString();
    }
}
