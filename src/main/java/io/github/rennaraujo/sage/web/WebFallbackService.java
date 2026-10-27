package io.github.rennaraujo.sage.web;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.rennaraujo.sage.audit.AuditEvent;
import io.github.rennaraujo.sage.audit.AuditEventType;
import io.github.rennaraujo.sage.audit.AuditException;
import io.github.rennaraujo.sage.audit.AuditLog;
import io.github.rennaraujo.sage.document.Document;
import io.github.rennaraujo.sage.document.DocumentId;
import io.github.rennaraujo.sage.document.DocumentMetadata;
import io.github.rennaraujo.sage.document.DocumentStore;
import io.github.rennaraujo.sage.document.InvalidDocumentException;
import io.github.rennaraujo.sage.document.Source;
import io.github.rennaraujo.sage.question.QuestionAnalysis;
import io.github.rennaraujo.sage.upload.SecretScanner;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fallback web: quando o acervo não tem a versão pública pedida (ou não a responde bem), busca a documentação oficial,
 * converte e grava como documento {@code publico/<linguagem>/<versao>/<slug>.md} com {@code revisado: false},
 * {@code url_origem} e {@code coletado_em}. O resto do pipeline (índice, cache, citações marcadas como não revisadas)
 * trata esses documentos como quaisquer outros.
 *
 * <p>Antes de qualquer saída para a internet o serviço grava {@code WEB_SEARCH} na auditoria (ator, linguagem, versão e
 * o SHA-256 da consulta, nunca o texto); se a gravação falha, a exceção sobe e nada sai (fail-closed). Cada página
 * passa por: URL cuja estrutura de caminho traz a versão pedida ({@link VersionedUrls}; sem isso, uma página de outra
 * versão seria gravada com o rótulo errado), download pelo {@link AllowlistedPageFetcher}, conversão pelo
 * {@link HtmlToMarkdown} (que arranca texto escondido), varredura de segredos (achou, descarta) e auditoria
 * {@code DOCUMENT_UPLOADED} com {@code origem=web} antes de gravar com {@link DocumentStore#saveIfAbsent}.
 *
 * <p>Cada chamada recebe o tempo que sobra do prazo único da pergunta ({@code sage.web.fallback-timeout}, todas as
 * versões somadas); passou, a thread é interrompida e vale o que já foi gravado. Duas perguntas simultâneas pela mesma
 * linguagem e versão não buscam duas vezes: a segunda espera a primeira e aproveita o que ela gravou. E a mesma
 * pergunta (linguagem, versão, texto) só aciona a busca uma vez por hora, tenha achado algo ou não, para não gastar a
 * cota do Brave a cada repetição.
 *
 * <p>Nada de pergunta, página ou URL vai para log: só contagens e a classe das falhas.
 */
public final class WebFallbackService implements WebFallback, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(WebFallbackService.class);
    private static final int SEARCH_LIMIT = 10;
    private static final int MAX_SLUG_CHARS = 100;
    private static final int MAX_SECTION_CHARS = 120;
    private static final Duration TRIED_TTL = Duration.ofHours(1);
    private static final long TRIED_MAX = 1000;
    private static final Pattern NON_SLUG = Pattern.compile("[^a-z0-9]+");
    private static final Pattern EXTENSION = Pattern.compile("\\.(?:html?|xhtml)$");
    private static final Pattern EDGE_DASHES = Pattern.compile("^-+|-+$");
    private static final Pattern LEADING_DASHES = Pattern.compile("^-+");
    private static final Pattern H1 = Pattern.compile("(?m)^#\\s+(.+?)\\s*#*\\s*$");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private final WebSearch search;
    private final AllowlistedPageFetcher fetcher;
    private final HtmlToMarkdown converter;
    private final WebQueryBuilder queryBuilder;
    private final WebProperties properties;
    private final DocumentStore store;
    private final SecretScanner scanner;
    private final AuditLog audit;
    private final Clock clock;
    private final VersionedUrls versionedUrls;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, CompletableFuture<List<Document>>> inFlight = new ConcurrentHashMap<>();
    private final Cache<String, Boolean> tried = Caffeine.newBuilder()
            .maximumSize(TRIED_MAX)
            .expireAfterWrite(TRIED_TTL)
            .build();

    public WebFallbackService(
            WebSearch search,
            AllowlistedPageFetcher fetcher,
            HtmlToMarkdown converter,
            WebQueryBuilder queryBuilder,
            WebProperties properties,
            DocumentStore store,
            SecretScanner scanner,
            AuditLog audit,
            Clock clock) {
        this(
                search,
                fetcher,
                converter,
                queryBuilder,
                properties,
                store,
                scanner,
                audit,
                clock,
                VersionedUrls.production());
    }

    /** Construtor dos testes: permite apontar hosts locais para as regras de um domínio oficial. */
    WebFallbackService(
            WebSearch search,
            AllowlistedPageFetcher fetcher,
            HtmlToMarkdown converter,
            WebQueryBuilder queryBuilder,
            WebProperties properties,
            DocumentStore store,
            SecretScanner scanner,
            AuditLog audit,
            Clock clock,
            VersionedUrls versionedUrls) {
        this.search = search;
        this.fetcher = fetcher;
        this.converter = converter;
        this.queryBuilder = queryBuilder;
        this.properties = properties;
        this.store = store;
        this.scanner = scanner;
        this.audit = audit;
        this.clock = clock;
        this.versionedUrls = versionedUrls;
    }

    @Override
    public boolean active() {
        return search.available();
    }

    @Override
    public boolean supports(String language) {
        return !properties.domainsFor(language).isEmpty();
    }

    @Override
    public int maxPages() {
        return properties.maxPagesPerQuestion();
    }

    @Override
    public Duration timeout() {
        return properties.fallbackTimeout();
    }

    /** Com o teto de páginas e o prazo inteiro configurados. */
    public List<Document> fetchAndStore(String question, QuestionAnalysis analysis, String version, String actor) {
        return fetchAndStore(
                question, analysis, version, actor, properties.maxPagesPerQuestion(), properties.fallbackTimeout());
    }

    @Override
    public List<Document> fetchAndStore(
            String question, QuestionAnalysis analysis, String version, String actor, int maxPages, Duration budget) {
        Optional<String> language = analysis.language();
        if (!active()
                || language.isEmpty()
                || !supports(language.get())
                || maxPages < 1
                || budget.isNegative()
                || budget.isZero()) {
            return List.of();
        }
        long deadline = System.nanoTime() + budget.toNanos();
        String triedKey = triedKey(language.get(), version, question);
        if (tried.getIfPresent(triedKey) != null) {
            return List.of();
        }
        String flightKey = language.get() + "|" + version;
        CompletableFuture<List<Document>> mine = new CompletableFuture<>();
        CompletableFuture<List<Document>> running = inFlight.putIfAbsent(flightKey, mine);
        if (running != null) {
            return awaitOther(running, deadline);
        }
        try {
            List<Document> saved =
                    run(question, analysis, language.get(), version, actor, maxPages, deadline, triedKey);
            mine.complete(saved);
            return saved;
        } catch (RuntimeException e) {
            mine.complete(List.of());
            throw e;
        } finally {
            inFlight.remove(flightKey, mine);
        }
    }

    private List<Document> awaitOther(CompletableFuture<List<Document>> running, long deadline) {
        try {
            return running.get(Math.max(1, remainingMillis(deadline)), TimeUnit.MILLISECONDS);
        } catch (TimeoutException | ExecutionException e) {
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }

    private List<Document> run(
            String question,
            QuestionAnalysis analysis,
            String language,
            String version,
            String actor,
            int maxPages,
            long deadline,
            String triedKey) {
        Set<String> domains = properties.domainsFor(language);
        QuestionAnalysis forVersion = new QuestionAnalysis(
                analysis.sources(),
                Optional.of(language),
                // A versão buscada vem primeiro (vai no prefixo); as demais só saem dos tokens da pergunta.
                Stream.concat(Stream.of(version), analysis.versions().stream())
                        .distinct()
                        .toList(),
                analysis.type(),
                analysis.mixed(),
                analysis.publicLanguageMatched());
        Optional<String> query = queryBuilder.build(question, forVersion);
        if (query.isEmpty()) {
            LOG.info("Fallback web não executado: a consulta carregaria um alias interno");
            return List.of();
        }
        // Fail-closed: sem registro da saída, ela não acontece. O texto da consulta nunca vai para a auditoria.
        audit.record(new AuditEvent(
                AuditEventType.WEB_SEARCH,
                actor,
                clock.instant(),
                Map.of("language", language, "version", version, "queryHash", sha256(query.get()))));
        tried.put(triedKey, Boolean.TRUE);
        List<Document> saved = new CopyOnWriteArrayList<>();
        Future<?> task;
        try {
            task = executor.submit(() -> collect(query.get(), domains, language, version, actor, maxPages, saved));
        } catch (RejectedExecutionException e) {
            // Serviço já encerrado (a aplicação está descendo).
            return List.of();
        }
        try {
            task.get(Math.max(1, remainingMillis(deadline)), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            task.cancel(true);
            LOG.warn("Fallback web excedeu o prazo; seguem as {} páginas já gravadas", saved.size());
        } catch (InterruptedException e) {
            task.cancel(true);
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            // Falha de auditoria é fail-closed: sobe para quem chamou, como em qualquer outra gravação auditada.
            if (e.getCause() instanceof RuntimeException failure && failure instanceof AuditException) {
                throw failure;
            }
            LOG.warn("Fallback web falhou ({})", e.getCause().getClass().getSimpleName());
        }
        LOG.info("Fallback web: {} páginas gravadas", saved.size());
        return List.copyOf(saved);
    }

    private void collect(
            String query,
            Set<String> domains,
            String language,
            String version,
            String actor,
            int maxPages,
            List<Document> saved) {
        for (URI listed : search.search(query, domains, SEARCH_LIMIT)) {
            if (saved.size() >= maxPages || Thread.currentThread().isInterrupted()) {
                return;
            }
            URI url = listed.normalize();
            if (!versionedUrls.matches(url, language, version)) {
                continue;
            }
            fetcher.fetch(url, domains)
                    // A URL final, depois dos redirects, é a que rotula o documento: tem de passar pela mesma regra.
                    .filter(page -> versionedUrls.matches(page.url().normalize(), language, version))
                    .flatMap(page -> toDocument(page, language, version))
                    .ifPresent(doc -> store(actor, doc, saved));
        }
    }

    /** Intenção auditada antes de gravar e resultado depois: {@code salvo}, {@code existente} ou {@code falhou}. */
    private void store(String actor, Document doc, List<Document> saved) {
        if (this.store.find(doc.metadata().id()).isPresent()) {
            return;
        }
        recordUpload(actor, doc, "tentativa");
        boolean written;
        try {
            written = this.store.saveIfAbsent(doc);
        } catch (RuntimeException e) {
            recordUpload(actor, doc, "falhou");
            throw e;
        }
        recordUpload(actor, doc, written ? "salvo" : "existente");
        if (written) {
            saved.add(doc);
        }
    }

    private void recordUpload(String actor, Document doc, String result) {
        audit.record(new AuditEvent(
                AuditEventType.DOCUMENT_UPLOADED,
                actor,
                clock.instant(),
                Map.of("document", doc.metadata().id().value(), "origem", "web", "resultado", result)));
    }

    private Optional<Document> toDocument(FetchedPage page, String language, String version) {
        String markdown = converter.convert(page.html());
        if (markdown.isBlank()) {
            return Optional.empty();
        }
        if (!scanner.scan(markdown).isEmpty()) {
            LOG.warn("Página descartada: o texto parece conter um segredo");
            return Optional.empty();
        }
        try {
            DocumentId id = DocumentId.of("publico/" + language + "/" + version + "/" + slug(page.url()) + ".md");
            DocumentMetadata metadata = new DocumentMetadata(
                    id,
                    Source.PUBLIC,
                    language,
                    Optional.of(version),
                    section(markdown, id),
                    List.of(),
                    Optional.of(page.url()),
                    LocalDate.now(clock),
                    false);
            return Optional.of(new Document(metadata, markdown + "\n"));
        } catch (InvalidDocumentException e) {
            return Optional.empty();
        }
    }

    /** Título da página (primeiro H1) ou, na falta, o último trecho do id. */
    private static String section(String markdown, DocumentId id) {
        Matcher h1 = H1.matcher(markdown);
        String title = h1.find() ? SPACES.matcher(h1.group(1)).replaceAll(" ").strip() : "";
        if (title.isEmpty()) {
            String value = id.value();
            title = value.substring(value.lastIndexOf('/') + 1, value.length() - ".md".length());
        }
        return title.length() > MAX_SECTION_CHARS ? title.substring(0, MAX_SECTION_CHARS) : title;
    }

    /** Caminho da URL em minúsculas, sem extensão, só com letras, dígitos e hífens. */
    static String slug(URI url) {
        String path = url.getPath() == null ? "" : url.getPath().toLowerCase(Locale.ROOT);
        String noExtension = EXTENSION.matcher(path).replaceFirst("");
        String slug = EDGE_DASHES
                .matcher(NON_SLUG.matcher(noExtension).replaceAll("-"))
                .replaceAll("");
        if (slug.length() > MAX_SLUG_CHARS) {
            slug = LEADING_DASHES
                    .matcher(slug.substring(slug.length() - MAX_SLUG_CHARS))
                    .replaceAll("");
        }
        return slug.isEmpty() ? "index" : slug;
    }

    private static long remainingMillis(long deadline) {
        return TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
    }

    private static String triedKey(String language, String version, String question) {
        String normalized =
                SPACES.matcher(question.toLowerCase(Locale.ROOT).strip()).replaceAll(" ");
        return language + "|" + version + "|" + sha256(normalized);
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível", e);
        }
    }

    /** Encerra as threads do fallback junto com a aplicação. */
    @Override
    public void close() {
        executor.shutdownNow();
    }
}
