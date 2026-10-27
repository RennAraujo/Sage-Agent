package io.github.rennaraujo.sage.question;

import io.github.rennaraujo.sage.document.DocumentDeletedEvent;
import io.github.rennaraujo.sage.document.DocumentMetadata;
import io.github.rennaraujo.sage.document.DocumentSavedEvent;
import io.github.rennaraujo.sage.document.DocumentStore;
import io.github.rennaraujo.sage.document.Source;
import java.io.IOException;
import java.io.InputStream;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Catálogo de nomes que identificam linguagens públicas (arquivo {@code aliases-publicos.yml}) e aplicações internas
 * (derivadas do {@code language} e dos {@code aliases} dos documentos INTERNAL). O lado interno é reconstruído sob
 * demanda depois de qualquer gravação ou remoção, para que um upload em lote não decifre o acervo a cada arquivo.
 */
@Component
public class AliasCatalog {

    private static final String RESOURCE = "aliases-publicos.yml";
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Duration RETRY_BACKOFF = Duration.ofSeconds(5);
    private static final Logger LOG = LoggerFactory.getLogger(AliasCatalog.class);
    /**
     * Conector de comparação, com artigo opcional depois de "e", "ou" e "para" ("do java 17 para o 21"), seguido de
     * versão; roda sobre texto normalizado (sem acento, minúsculo).
     */
    private static final Pattern CHAINED =
            Pattern.compile("\\s*(?:,|/|->|\\b(?:e|ou|para)(?:\\s+[oa])?\\b|\\bvs\\.?|\\bversus\\b|\\bx\\b)\\s*"
                    + "(\\d{1,3}\\.\\d{1,3}(?:\\.\\d{1,3})?|\\d{1,2}(?!\\d|\\.\\d))");
    /** Número seguido de substantivo contável ("3 passos") é quantidade, não versão. */
    private static final Pattern COUNTED = Pattern.compile("\\s+(?:passos?|vezes|itens?|items?|minutos?|segundos?|"
            + "horas?|dias?|semanas?|meses|anos?|exemplos?|linhas?|classes?|metodos?|arquivos?|threads?|usuarios?|"
            + "pessoas?|registros?|bytes?|requisicoes|erros?|testes?|campos?|etapas?|fases?)\\b");

    private static final Pattern BOUNDARY = Pattern.compile(
            "(?<=\\p{Ll})(?=\\p{Lu})|(?<=\\p{Lu})(?=\\p{Lu}\\p{Ll})|(?<=\\p{L})(?=\\p{N})|(?<=\\p{N})(?=\\p{L})");
    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");
    /** Abaixo disso a busca por trecho dentro de palavras geraria falso positivo demais; vale só a palavra inteira. */
    private static final int MIN_COMPACT_ALIAS = 4;

    private static final String BEFORE = "(?<![\\p{L}\\p{N}])";
    private static final String AFTER_LETTERS = "(?!\\p{L})";
    private static final String AFTER_ANY = "(?![\\p{L}\\p{N}])";

    /** Uma linguagem pública: nomes que a citam e regex que extrai o número da versão. */
    public record PublicLanguage(String id, List<Pattern> namePatterns, Pattern versionPattern) {

        boolean mentionedIn(String normalized) {
            return namePatterns.stream().anyMatch(p -> p.matcher(normalized).find());
        }

        int firstMention(String normalized) {
            int best = Integer.MAX_VALUE;
            for (Pattern p : namePatterns) {
                Matcher m = p.matcher(normalized);
                if (m.find()) {
                    best = Math.min(best, m.start());
                }
            }
            return best;
        }

        /**
         * Versões citadas, em texto normalizado. Depois de cada versão achada pela regex da linguagem, aceita as
         * encadeadas por conector de comparação ("3.5 e 4.1", "8 vs 17 para records"). A versão principal nunca é
         * descartada. Já o número encadeado seguido de substantivo contável ("java 17 e 3 exemplos") é quantidade
         * e não entra; anos de quatro dígitos também não, porque as regexes só aceitam até três dígitos.
         */
        List<String> versionsIn(String normalized) {
            List<String> found = new ArrayList<>();
            Matcher m = versionPattern.matcher(normalized);
            while (m.find()) {
                found.add(m.group(1));
                Matcher next = CHAINED.matcher(normalized);
                next.region(m.end(), normalized.length());
                while (next.lookingAt() && !isCounted(normalized, next.end())) {
                    found.add(next.group(1));
                    next.region(next.end(), normalized.length());
                }
            }
            return found;
        }

        private static boolean isCounted(String text, int from) {
            Matcher counted = COUNTED.matcher(text);
            counted.region(from, text.length());
            return counted.lookingAt();
        }
    }

    /** Fotografia do lado interno, marcada com a geração do acervo em que a leitura começou. */
    private record Internal(long generation, Set<String> aliases, List<Pattern> patterns, Set<String> compact) {}

    private static final Internal EMPTY = new Internal(-1, Set.of(), List.of(), Set.of());

    private final DocumentStore store;
    private final List<PublicLanguage> publicLanguages;
    private final Map<String, String> displayNames;
    private final AtomicLong generation = new AtomicLong();
    private final Clock clock;
    private final Object rebuildLock = new Object();
    private volatile Internal internal;
    private volatile Instant lastFailure;

    @Autowired
    public AliasCatalog(DocumentStore store) {
        this(store, Clock.systemUTC());
    }

    AliasCatalog(DocumentStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
        Map<String, Map<String, Object>> definitions = loadDefinitions();
        this.publicLanguages = publicLanguages(definitions);
        this.displayNames = displayNames(definitions);
    }

    @EventListener
    @Order(0)
    public void onSaved(DocumentSavedEvent event) {
        generation.incrementAndGet();
    }

    @EventListener
    @Order(0)
    public void onDeleted(DocumentDeletedEvent event) {
        generation.incrementAndGet();
    }

    /** Carrega o catálogo interno assim que a aplicação sobe, para a primeira pergunta não pagar a leitura. */
    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        snapshot();
    }

    List<PublicLanguage> publicLanguages() {
        return publicLanguages;
    }

    /** Nome legível da linguagem pública ("Java", "Spring Boot"); o próprio id se o YAML não trouxer {@code nome}. */
    String displayName(String languageId) {
        return displayNames.getOrDefault(languageId, languageId);
    }

    Set<String> internalAliases() {
        return snapshot().aliases();
    }

    /**
     * Diz se o texto cita algum alias interno, mesmo disfarçado: {@code billing_core_api}, {@code api.billing.core},
     * {@code BillingCoreApi}. Além do casamento por palavra inteira, compara o texto sem separadores (e sem caixa nem
     * acento) com cada alias também sem separadores, o que pega alias dentro de tokens compostos. Existe para quem
     * manda texto para fora (busca web) conferir que nada interno vai junto.
     */
    public boolean containsInternalAlias(String text) {
        String normalized = normalize(text);
        Internal current = snapshot();
        String split = normalize(splitBoundaries(text));
        if (current.patterns().stream()
                .anyMatch(p -> p.matcher(normalized).find() || p.matcher(split).find())) {
            return true;
        }
        String compactText = compact(normalized);
        return current.compact().stream().anyMatch(compactText::contains);
    }

    /** {@code ErpClient} vira {@code Erp Client}; {@code sga2} vira {@code sga 2}. Roda antes de minúsculas. */
    static String splitBoundaries(String text) {
        return BOUNDARY.matcher(text).replaceAll(" ");
    }

    private static String compact(String text) {
        return NON_ALNUM.matcher(normalize(text)).replaceAll("");
    }

    /** Casa os aliases no texto como veio e também separado em camelCase e letra/dígito ({@code ErpClient}). */
    boolean mentionsInternal(String text) {
        return matchesInternal(normalize(text)) || matchesInternal(normalize(splitBoundaries(text)));
    }

    boolean matchesInternal(String normalized) {
        return snapshot().patterns().stream()
                .anyMatch(p -> p.matcher(normalized).find());
    }

    /** Minúsculas, sem acento e com espaços colapsados: forma usada para comparar nomes. */
    static String normalize(String text) {
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFD);
        String plain = MARKS.matcher(decomposed).replaceAll("");
        return SPACES.matcher(plain.toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
    }

    /**
     * Devolve a fotografia vigente. A geração é lida antes da leitura do acervo: um evento que chegue durante a
     * reconstrução deixa a fotografia publicada com geração velha, e a próxima chamada reconstrói. Uma
     * reconstrução por vez; quem espera reaproveita o resultado. Se a leitura falha, segue valendo a última
     * fotografia boa (ou vazia, se nunca houve uma); só a classe da exceção vai para o log.
     */
    private Internal snapshot() {
        Internal current = internal;
        if (current != null && current.generation() == generation.get()) {
            return current;
        }
        synchronized (rebuildLock) {
            long startedAt = generation.get();
            current = internal;
            if (current != null && current.generation() == startedAt) {
                return current;
            }
            Instant failedAt = lastFailure;
            if (failedAt != null && clock.instant().isBefore(failedAt.plus(RETRY_BACKOFF))) {
                return current != null ? current : EMPTY;
            }
            try {
                Internal built = buildInternal(startedAt);
                internal = built;
                lastFailure = null;
                return built;
            } catch (RuntimeException e) {
                lastFailure = clock.instant();
                LOG.warn(
                        "Falha ao recarregar o catálogo de aliases internos ({})",
                        e.getClass().getName());
                return current != null ? current : EMPTY;
            }
        }
    }

    private Internal buildInternal(long startedAt) {
        Set<String> aliases = new LinkedHashSet<>();
        for (DocumentMetadata meta : store.listMetadata()) {
            if (meta.source() != Source.INTERNAL) {
                continue;
            }
            addAlias(aliases, meta.language());
            meta.aliases().forEach(a -> addAlias(aliases, a));
        }
        List<Pattern> patterns =
                aliases.stream().map(a -> namePattern(a, AFTER_ANY)).toList();
        Set<String> compact = aliases.stream()
                .map(AliasCatalog::compact)
                .filter(c -> c.length() >= MIN_COMPACT_ALIAS)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new Internal(startedAt, Set.copyOf(aliases), patterns, compact);
    }

    private static void addAlias(Set<String> aliases, String raw) {
        String name = normalize(raw);
        if (name.isEmpty()) {
            return;
        }
        aliases.add(name);
        String spaced = name.replace('-', ' ').replace('_', ' ');
        if (!spaced.equals(name)) {
            aliases.add(SPACES.matcher(spaced).replaceAll(" ").strip());
        }
    }

    private static Pattern namePattern(String name, String after) {
        String body =
                SPACES.splitAsStream(name).map(Pattern::quote).collect(java.util.stream.Collectors.joining("\\s+"));
        return Pattern.compile(BEFORE + body + after);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> loadDefinitions() {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Object root;
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            root = new Yaml(new SafeConstructor(options)).load(in);
        } catch (IOException e) {
            throw new IllegalStateException("Não foi possível ler " + RESOURCE, e);
        }
        return (Map<String, Map<String, Object>>) ((Map<String, Object>) root).get("languages");
    }

    @SuppressWarnings("unchecked")
    private static List<PublicLanguage> publicLanguages(Map<String, Map<String, Object>> languages) {
        List<PublicLanguage> result = new ArrayList<>();
        languages.forEach((id, def) -> {
            List<Pattern> names = ((List<String>) def.get("names"))
                    .stream().map(n -> namePattern(normalize(n), AFTER_LETTERS)).toList();
            Pattern version = Pattern.compile((String) def.get("version-pattern"));
            result.add(new PublicLanguage(id, names, version));
        });
        return List.copyOf(result);
    }

    private static Map<String, String> displayNames(Map<String, Map<String, Object>> languages) {
        Map<String, String> names = new HashMap<>();
        languages.forEach((id, def) -> {
            if (def.get("nome") instanceof String name && !name.isBlank()) {
                names.put(id, name);
            }
        });
        return Map.copyOf(names);
    }
}
