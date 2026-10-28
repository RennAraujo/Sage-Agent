package io.github.rennaraujo.sage.question;

import io.github.rennaraujo.sage.question.AliasCatalog.PublicLanguage;
import io.github.rennaraujo.sage.search.RerankQueryCleaner;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Tira da pergunta, para o reranker, o que já virou filtro: versões ("java 21", "3.5 e 4.1"), nomes de linguagem e as
 * frases de comparação ("existe no", "é exclusivo do", "em qual versão"). Sobra o assunto. Os padrões de linguagem e de
 * versão são os do catálogo ({@code aliases-publicos.yml}), aplicados sem diferenciar caixa; os acentos ficam, porque o
 * reranker lê o texto original.
 *
 * <p>Números encadeados ("java 17, 21 e 25") só saem quando vêm logo depois de uma versão reconhecida e não são
 * quantidade ("java 21 e 3 exemplos" mantém o "3 exemplos").
 */
@Component
public final class RerankQueryTextCleaner implements RerankQueryCleaner {

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    /** Marca no lugar de uma versão achada; letras só, para não colidir com nenhum padrão. */
    private static final String MARK = "zzversaozz";

    private static final String COUNTED = "(?!\\s*(?:exemplos?|passos?|vezes|itens?|items?|linhas?|classes?|m[eé]todos?"
            + "|arquivos?|threads?|usu[aá]rios?|registros?|campos?|etapas?|fases?|por\\s+vez|minutos?|segundos?|horas?"
            + "|dias?)\\b)";
    private static final Pattern CHAINED_AFTER_VERSION = Pattern.compile(
            MARK
                    + "(?:\\s*(?:,|/|\\bvs\\.?|\\bversus\\b|\\b(?:e|ou|para)(?:\\s+[oa])?\\b)"
                    + "\\s*\\d{1,3}(?:\\.\\d{1,3})*\\b"
                    + COUNTED + ")*",
            FLAGS);
    private static final Pattern COMPARISON_PHRASE = Pattern.compile(
            "\\bexiste\\s+(?:no|na|em)\\b|\\bexclusiv[oa]s?(?:\\s+d[oa]s?)?\\b"
                    + "|\\bdispon[ií]vel\\s+(?:no|na|em|a partir)\\b"
                    + "|\\ba partir\\s+(?:de qual|d[oa])\\s+vers[aã]o\\b|\\b(?:em\\s+)?qual\\s+vers[aã]o\\b"
                    + "|\\bfoi\\s+(?:removid|introduzid)[oa]\\b|\\bdiferen[cç]as?(?:\\s+entre)?\\b"
                    + "|\\bmigra(?:r|[cç][aã]o)\\b|\\bcomparar\\b|\\bversus\\b|\\bvs\\.?",
            FLAGS);
    private static final String CONNECTORS = "(?:e|ou|é|no|na|em|do|da|de|para|a|o)";
    private static final Pattern TRAILING_CONNECTORS =
            Pattern.compile("(?:\\s+" + CONNECTORS + "(?![\\p{L}\\p{N}]))+(?=\\s*(?:$|[?.!,]))", FLAGS);
    private static final Pattern LEADING_CONNECTORS =
            Pattern.compile("^(?:\\s*" + CONNECTORS + "(?![\\p{L}\\p{N}]))+\\s+", FLAGS);
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern SPACE_BEFORE_PUNCTUATION = Pattern.compile("\\s+([?.!,])");
    private static final int MIN_LENGTH = 3;

    private final AliasCatalog catalog;

    public RerankQueryTextCleaner(AliasCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public String clean(String question) {
        String text = question;
        List<PublicLanguage> languages = catalog.publicLanguages();
        for (PublicLanguage language : languages) {
            text = replaceAll(text, language.versionPattern(), " " + MARK + " ");
        }
        text = CHAINED_AFTER_VERSION
                .matcher(text.replace(MARK + " " + MARK, MARK))
                .replaceAll(" ");
        text = text.replace(MARK, " ");
        for (PublicLanguage language : languages) {
            for (Pattern name : language.namePatterns()) {
                text = replaceAll(text, name, " ");
            }
        }
        text = COMPARISON_PHRASE.matcher(text).replaceAll(" ");
        text = TRAILING_CONNECTORS.matcher(text).replaceAll("");
        text = LEADING_CONNECTORS.matcher(text.strip()).replaceAll("");
        text = SPACES.matcher(text).replaceAll(" ").strip();
        text = SPACE_BEFORE_PUNCTUATION.matcher(text).replaceAll("$1");
        return text.length() < MIN_LENGTH ? question : text;
    }

    private static String replaceAll(String text, Pattern pattern, String replacement) {
        return Pattern.compile(pattern.pattern(), FLAGS).matcher(text).replaceAll(replacement);
    }
}
