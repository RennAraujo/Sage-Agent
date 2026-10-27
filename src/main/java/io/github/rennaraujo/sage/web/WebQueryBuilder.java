package io.github.rennaraujo.sage.web;

import io.github.rennaraujo.sage.question.AliasCatalog;
import io.github.rennaraujo.sage.question.QuestionAnalysis;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Monta a consulta que sai para o buscador: nome da linguagem, versão e as palavras da pergunta que carregam
 * significado. Essa string vai para um serviço de terceiros, então nada que identifique o lado interno pode estar nela.
 *
 * <p>A proteção tem duas camadas. Primeiro, tokens e janelas de até {@value #MAX_ALIAS_WINDOW} tokens consecutivos que
 * citam um alias interno são descartados ({@link AliasCatalog#containsInternalAlias}), o que cobre também formas
 * compostas como {@code billing_core_api}, {@code api.billing.core} e {@code BillingCoreApi}. Depois, a consulta já
 * montada é conferida de novo; se ainda houver alias, a busca é abortada (resultado vazio) em vez de enviar o risco.
 * Stopwords em português saem porque o buscador já recebe a linguagem e a versão como contexto.
 */
public final class WebQueryBuilder {

    /** Teto da consulta. O Brave aceita 400 caracteres; o resto fica para o filtro {@code site:} dos domínios. */
    static final int MAX_QUERY_CHARS = 150;

    /** Maior número de palavras seguidas que um alias pode ter e ainda ser achado por janela. */
    static final int MAX_ALIAS_WINDOW = 6;

    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}_.#+]+");
    private static final Pattern WORD_SPLIT = Pattern.compile("[\\s_-]+");
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final String EDGE_PUNCTUATION = ".";

    private static final Set<String> STOPWORDS = Set.of(
            "a", "o", "as", "os", "um", "uma", "uns", "umas", "de", "do", "da", "dos", "das", "em", "no", "na", "nos",
            "nas", "por", "pelo", "pela", "pelos", "pelas", "para", "pra", "com", "sem", "sobre", "ao", "aos", "que",
            "e", "ou", "mas", "se", "como", "qual", "quais", "quando", "onde", "quem", "porque", "ser", "sao", "eh",
            "foi", "era", "tem", "ter", "ha", "mais", "menos", "muito", "isso", "isto", "esse", "essa", "este", "esta",
            "esses", "essas", "estes", "estas", "meu", "minha", "seu", "sua", "nosso", "nossa", "ele", "ela", "eles",
            "elas", "eu", "voce", "pode", "posso", "podem", "deve", "devo", "existe", "ja", "so", "entre", "ate",
            "tambem", "ainda", "la", "aqui", "me", "te", "lhe", "vs", "versus");

    private final AliasCatalog aliases;

    public WebQueryBuilder(AliasCatalog aliases) {
        this.aliases = aliases;
    }

    /** A consulta, ou vazio se não foi possível garantir que ela não carrega nada interno. */
    public Optional<String> build(String question, QuestionAnalysis analysis) {
        String language = analysis.language().orElse("");
        String prefix = prefix(language, analysis);
        Set<String> contextWords = contextWords(language, analysis);
        List<String> tokens = tokens(question);
        boolean[] dropped = markAliases(tokens);

        Set<String> seen = new HashSet<>();
        List<String> kept = new ArrayList<>();
        int length = prefix.length();
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            String norm = fold(token);
            if (dropped[i] || STOPWORDS.contains(norm) || contextWords.contains(norm) || !seen.add(norm)) {
                continue;
            }
            if (length + 1 + token.length() > MAX_QUERY_CHARS) {
                break;
            }
            kept.add(token);
            length += 1 + token.length();
        }
        String body = String.join(" ", kept);
        if (aliases.containsInternalAlias(body)) {
            return Optional.empty();
        }
        return Optional.of((prefix + " " + body).strip());
    }

    private static String prefix(String language, QuestionAnalysis analysis) {
        String name = displayName(language);
        String version =
                analysis.versions().isEmpty() ? "" : analysis.versions().get(0);
        return Stream.of(name, version).filter(s -> !s.isBlank()).collect(Collectors.joining(" "));
    }

    /** "spring-boot" vira "Spring Boot". */
    private static String displayName(String languageId) {
        return WORD_SPLIT
                .splitAsStream(languageId)
                .filter(w -> !w.isBlank())
                .map(w -> w.substring(0, 1).toUpperCase(Locale.ROOT) + w.substring(1))
                .collect(Collectors.joining(" "));
    }

    /** Palavras que o prefixo já cobre, para não repeti-las. */
    private static Set<String> contextWords(String language, QuestionAnalysis analysis) {
        Set<String> words = new HashSet<>();
        WORD_SPLIT.splitAsStream(language).filter(w -> !w.isBlank()).forEach(w -> words.add(fold(w)));
        analysis.versions().forEach(v -> words.add(fold(v)));
        return words;
    }

    private static List<String> tokens(String question) {
        List<String> out = new ArrayList<>();
        var matcher = TOKEN.matcher(question);
        while (matcher.find()) {
            String token = stripEdges(matcher.group());
            if (!token.isEmpty()) {
                out.add(token);
            }
        }
        return out;
    }

    private static String stripEdges(String token) {
        int start = 0;
        int end = token.length();
        while (start < end && EDGE_PUNCTUATION.indexOf(token.charAt(start)) >= 0) {
            start++;
        }
        while (end > start && EDGE_PUNCTUATION.indexOf(token.charAt(end - 1)) >= 0) {
            end--;
        }
        return token.substring(start, end);
    }

    /**
     * Marca os tokens que fazem parte de uma citação a alias. Um token sozinho que cita; senão a menor janela de
     * tokens consecutivos que cita (sem que a janela encurtada de qualquer ponta ainda cite), para não descartar
     * palavra inocente ao lado.
     */
    private boolean[] markAliases(List<String> tokens) {
        boolean[] dropped = new boolean[tokens.size()];
        for (int start = 0; start < tokens.size(); start++) {
            for (int len = 1; len <= MAX_ALIAS_WINDOW && start + len <= tokens.size(); len++) {
                if (cites(tokens, start, len)
                        && (len == 1 || (!cites(tokens, start + 1, len - 1) && !cites(tokens, start, len - 1)))) {
                    for (int k = start; k < start + len; k++) {
                        dropped[k] = true;
                    }
                    break;
                }
            }
        }
        return dropped;
    }

    private boolean cites(List<String> tokens, int start, int len) {
        return aliases.containsInternalAlias(String.join(" ", tokens.subList(start, start + len)));
    }

    private static String fold(String text) {
        String plain =
                MARKS.matcher(Normalizer.normalize(text, Normalizer.Form.NFD)).replaceAll("");
        return plain.toLowerCase(Locale.ROOT).strip();
    }
}
