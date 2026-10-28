package io.github.rennaraujo.sage.web;

import io.github.rennaraujo.sage.audit.AuditEvent;
import io.github.rennaraujo.sage.audit.AuditEventType;
import io.github.rennaraujo.sage.audit.AuditLog;
import io.github.rennaraujo.sage.auth.RateLimitedAction;
import io.github.rennaraujo.sage.auth.RateLimiter;
import io.github.rennaraujo.sage.document.Document;
import io.github.rennaraujo.sage.document.DocumentChangeNotPropagatedException;
import io.github.rennaraujo.sage.document.DocumentDefaults;
import io.github.rennaraujo.sage.document.DocumentId;
import io.github.rennaraujo.sage.document.DocumentStore;
import io.github.rennaraujo.sage.document.FrontmatterParser;
import io.github.rennaraujo.sage.document.InvalidDocumentException;
import io.github.rennaraujo.sage.shared.RateLimitExceededException;
import io.github.rennaraujo.sage.upload.SecretScanner;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Semeia o acervo público a partir de uma página raiz de documentação oficial. Cada importação é um job em memória,
 * executado numa virtual thread: percorre as páginas em largura, só no mesmo host (e porta) e dentro do prefixo de
 * path da raiz, até {@code max-depth} níveis e {@code max-pages} páginas. Os downloads passam todos pelo
 * {@link AllowlistedPageFetcher}, então valem as mesmas travas contra SSRF do fallback web, e o texto sai pelo mesmo
 * {@link HtmlToMarkdown}. Nada é gravado em claro: as páginas só existem em memória até o {@link DocumentStore} cifrar.
 *
 * <p>Cada página vira o documento {@code publico/<linguagem>/<versao>/<slug>.md} com {@code revisado: false}, {@code
 * url_origem} e {@code coletado_em}. Em domínio oficial com estrutura de versão conhecida ({@link VersionedUrls}), a
 * raiz precisa ser da versão pedida (422 se não for) e cada página passa pela mesma regra. Página com segredo detectado
 * é ignorada; documento que já existe no acervo também é ignorado, nunca sobrescrito: a gravação usa {@link
 * DocumentStore#saveIfAbsent}, atômica, então nem um segundo job nem um upload concorrente são atropelados. Duas
 * páginas do mesmo job que dão o mesmo slug contam como colisão. A auditoria vem antes de cada gravação e, se falhar, o
 * job para (fail-closed). Só ids e contagens entram no log.
 *
 * <p>Limites de execução: no máximo {@code max-concurrent-jobs} jobs ao mesmo tempo (o seguinte recebe 429), prazo
 * total {@code max-duration} por job, e um registro que nunca descarta job em andamento. Cancelamento (prazo, parada
 * da aplicação) só é visto entre páginas, nunca entre a auditoria e a gravação de uma página.
 */
final class ImportService {

    /** Mensagem única para qualquer raiz recusada: nunca repete a URL recebida. */
    static final String INVALID_ROOT =
            "A URL raiz precisa ser https, sem usuário e senha, de um domínio permitido para a linguagem informada";

    /** Raiz de domínio oficial cuja estrutura de caminho traz outra versão; também não repete a URL. */
    static final String ROOT_OF_ANOTHER_VERSION =
            "A URL raiz não é da versão informada: neste domínio a versão faz parte do caminho e precisa ser a mesma";

    private static final Logger LOG = LoggerFactory.getLogger(ImportService.class);
    private static final String HTTPS = "https";
    private static final String HTTP = "http";
    private static final int HTTPS_PORT = 443;
    private static final int HTTP_PORT = 80;
    private static final int MAX_SLUG_CHARS = 80;
    private static final int MAX_SECTION_CHARS = 200;
    private static final String DEFAULT_SLUG = "index";
    private static final String DELIMITER = "---";
    private static final char NEXT_LINE = '\u0085';
    private static final char LINE_SEPARATOR = ' ';
    private static final char PARAGRAPH_SEPARATOR = ' ';
    private static final Duration BUSY_RETRY_AFTER = Duration.ofSeconds(30);
    private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(2);
    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");
    private static final Pattern NON_SLUG = Pattern.compile("[^a-z0-9]+");
    private static final Pattern EDGE_DASHES = Pattern.compile("^-+|-+$");
    private static final Pattern PAGE_EXTENSION = Pattern.compile("(?i)\\.html?$");

    private final AllowlistedPageFetcher fetcher;
    private final HtmlToMarkdown converter;
    private final DocumentStore store;
    private final FrontmatterParser parser;
    private final SecretScanner scanner;
    private final AuditLog auditLog;
    private final RateLimiter rateLimiter;
    private final WebProperties web;
    private final ImportProperties properties;
    private final VersionedUrls versionedUrls;
    private final Clock clock;
    private final boolean testMode;
    private final Semaphore permits;
    /** Em ordem de criação. Todo acesso é sincronizado em {@code jobs}. */
    private final Map<String, JobState> jobs = new LinkedHashMap<>();

    ImportService(
            AllowlistedPageFetcher fetcher,
            HtmlToMarkdown converter,
            DocumentStore store,
            FrontmatterParser parser,
            SecretScanner scanner,
            AuditLog auditLog,
            RateLimiter rateLimiter,
            WebProperties web,
            ImportProperties properties,
            Clock clock) {
        this(fetcher, converter, store, parser, scanner, auditLog, rateLimiter, web, properties, clock, false);
    }

    /** Construtor dos testes: em {@code testMode} aceita raízes e links {@code http} (o fetcher também). */
    ImportService(
            AllowlistedPageFetcher fetcher,
            HtmlToMarkdown converter,
            DocumentStore store,
            FrontmatterParser parser,
            SecretScanner scanner,
            AuditLog auditLog,
            RateLimiter rateLimiter,
            WebProperties web,
            ImportProperties properties,
            Clock clock,
            boolean testMode) {
        this(
                fetcher,
                converter,
                store,
                parser,
                scanner,
                auditLog,
                rateLimiter,
                web,
                properties,
                clock,
                testMode,
                VersionedUrls.production());
    }

    /** Construtor dos testes que também aponta um host local para as regras de versão de um domínio oficial. */
    ImportService(
            AllowlistedPageFetcher fetcher,
            HtmlToMarkdown converter,
            DocumentStore store,
            FrontmatterParser parser,
            SecretScanner scanner,
            AuditLog auditLog,
            RateLimiter rateLimiter,
            WebProperties web,
            ImportProperties properties,
            Clock clock,
            boolean testMode,
            VersionedUrls versionedUrls) {
        this.versionedUrls = versionedUrls;
        this.fetcher = fetcher;
        this.converter = converter;
        this.store = store;
        this.parser = parser;
        this.scanner = scanner;
        this.auditLog = auditLog;
        this.rateLimiter = rateLimiter;
        this.web = web;
        this.properties = properties;
        this.clock = clock;
        this.testMode = testMode;
        this.permits = new Semaphore(properties.maxConcurrentJobs());
    }

    /**
     * Valida, consome uma permissão do limite de upload, reserva um lugar de execução (429 se não houver), audita
     * {@code IMPORT_STARTED} e só então dispara o job. Se a auditoria falhar, a exceção sobe, o lugar é devolvido e
     * nenhum job existe.
     */
    ImportJob start(URI root, String language, String version, int depth, String actor) {
        rateLimiter.require(actor, RateLimitedAction.UPLOAD);
        String lang = language == null ? "" : language.strip().toLowerCase(Locale.ROOT);
        String ver = version == null ? "" : version.strip().toLowerCase(Locale.ROOT);
        Set<String> domains = web.domainsFor(lang);
        URI start = normalizeRoot(root, domains);
        if (versionedUrls.hasRules(start) && !versionedUrls.matches(start, lang, ver)) {
            // Domínio oficial com estrutura de versão conhecida: a raiz tem de ser da versão pedida, senão cada página
            // seria gravada com o rótulo de outra versão.
            throw new InvalidDocumentException(ROOT_OF_ANOTHER_VERSION);
        }
        if (depth < 0 || depth > properties.maxDepth()) {
            throw new InvalidDocumentException("profundidade deve ficar entre 0 e " + properties.maxDepth());
        }
        // Valida linguagem e versão do mesmo jeito que o acervo: se o id não fecha, nenhuma página fecharia.
        DocumentId.of("publico/" + lang + "/" + ver + "/" + DEFAULT_SLUG + ".md");

        if (!permits.tryAcquire()) {
            throw new RateLimitExceededException(BUSY_RETRY_AFTER);
        }
        boolean launched = false;
        try {
            if (!registryHasRoom()) {
                throw new RateLimitExceededException(BUSY_RETRY_AFTER);
            }
            JobState state = new JobState(UUID.randomUUID().toString(), start, lang, ver);
            Map<String, String> details = new LinkedHashMap<>();
            details.put("job", state.id);
            details.put("host", start.getHost());
            details.put("linguagem", lang);
            details.put("versao", ver);
            details.put("profundidade", Integer.toString(depth));
            audit(AuditEventType.IMPORT_STARTED, actor, details);

            register(state);
            Run run = new Run(state, depth, domains, actor);
            try {
                state.thread =
                        Thread.ofVirtual().name("sage-import-" + state.id).unstarted(() -> execute(run));
                state.thread.start();
            } catch (RuntimeException e) {
                state.finish(ImportJob.Status.FAILED);
                throw e;
            }
            launched = true;
            return state.snapshot();
        } finally {
            if (!launched) {
                permits.release();
            }
        }
    }

    Optional<ImportJob> status(String id) {
        synchronized (jobs) {
            return Optional.ofNullable(jobs.get(id)).map(JobState::snapshot);
        }
    }

    /**
     * Parada da aplicação: sinaliza o cancelamento dos jobs em andamento (visto entre páginas), espera um pouco e
     * marca como FAILED os que ainda não terminaram. O que já foi gravado fica.
     */
    void shutdown() {
        List<JobState> running;
        synchronized (jobs) {
            running = jobs.values().stream().filter(JobState::isRunning).toList();
        }
        running.forEach(s -> s.cancelled = true);
        long deadline = System.nanoTime() + SHUTDOWN_GRACE.toNanos();
        for (JobState state : running) {
            Thread thread = state.thread;
            long left = deadline - System.nanoTime();
            if (thread != null && left > 0) {
                try {
                    thread.join(Duration.ofNanos(left));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        running.forEach(s -> s.finish(ImportJob.Status.FAILED));
    }

    private boolean registryHasRoom() {
        synchronized (jobs) {
            return jobs.size() < properties.jobsKept() || jobs.values().stream().anyMatch(s -> !s.isRunning());
        }
    }

    /** Insere o job; se o registro está cheio, descarta o mais antigo já terminado. Nunca descarta job em andamento. */
    private void register(JobState state) {
        synchronized (jobs) {
            if (jobs.size() >= properties.jobsKept()) {
                String oldestFinished = jobs.values().stream()
                        .filter(s -> !s.isRunning())
                        .map(s -> s.id)
                        .findFirst()
                        .orElseThrow(() -> new RateLimitExceededException(BUSY_RETRY_AFTER));
                jobs.remove(oldestFinished);
            }
            jobs.put(state.id, state);
        }
    }

    private void audit(AuditEventType type, String actor, Map<String, String> details) {
        auditLog.record(new AuditEvent(type, actor, clock.instant(), details));
    }

    // ---- raiz ----------------------------------------------------------------------------------------------------

    private URI normalizeRoot(URI root, Set<String> domains) {
        if (root == null
                || root.getHost() == null
                || root.getUserInfo() != null
                || !schemeAllowed(root.getScheme())
                || !AllowedHosts.matches(root.getHost(), domains)) {
            throw new InvalidDocumentException(INVALID_ROOT);
        }
        return canonical(root).orElseThrow(() -> new InvalidDocumentException(INVALID_ROOT));
    }

    private boolean schemeAllowed(String scheme) {
        return HTTPS.equalsIgnoreCase(scheme) || (testMode && HTTP.equalsIgnoreCase(scheme));
    }

    /** Minúsculas no esquema e no host, sem fragmento, query nem userinfo; caminho sem {@code ..}. */
    private static Optional<URI> canonical(URI uri) {
        if (uri.getHost() == null || uri.getScheme() == null) {
            return Optional.empty();
        }
        String rawPath = uri.getRawPath() == null ? "" : uri.getRawPath();
        if (UrlPaths.hasEncodedDotOrSlash(rawPath)) {
            return Optional.empty();
        }
        try {
            URI clean = new URI(
                            uri.getScheme().toLowerCase(Locale.ROOT),
                            null,
                            uri.getHost().toLowerCase(Locale.ROOT),
                            uri.getPort(),
                            rawPath.isEmpty() ? "/" : uri.getPath(),
                            null,
                            null)
                    .normalize();
            return Optional.of(clean);
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }

    // ---- job -----------------------------------------------------------------------------------------------------

    /** Estado de um job: contadores atômicos (a thread do job escreve, qualquer uma lê) e status de saída única. */
    private static final class JobState {
        final String id;
        final URI root;
        final String language;
        final String version;
        final AtomicInteger imported = new AtomicInteger();
        final AtomicInteger skipped = new AtomicInteger();
        final AtomicInteger failed = new AtomicInteger();
        final AtomicInteger collisions = new AtomicInteger();
        final AtomicReference<ImportJob.Status> status = new AtomicReference<>(ImportJob.Status.RUNNING);
        volatile boolean cancelled;
        volatile Thread thread;

        JobState(String id, URI root, String language, String version) {
            this.id = id;
            this.root = root;
            this.language = language;
            this.version = version;
        }

        boolean isRunning() {
            return status.get() == ImportJob.Status.RUNNING;
        }

        void finish(ImportJob.Status result) {
            status.compareAndSet(ImportJob.Status.RUNNING, result);
        }

        ImportJob snapshot() {
            return new ImportJob(
                    id,
                    root,
                    language,
                    version,
                    status.get(),
                    imported.get(),
                    skipped.get(),
                    failed.get(),
                    collisions.get());
        }
    }

    private record Run(JobState state, int depth, Set<String> domains, String actor) {}

    private record Queued(URI uri, int depth) {}

    /** Roda o job e garante um status final e a devolução do lugar de execução, aconteça o que acontecer. */
    private void execute(Run run) {
        JobState state = run.state();
        ImportJob.Status result = ImportJob.Status.FAILED;
        try {
            boolean complete = crawl(run) && !state.cancelled;
            result = complete ? ImportJob.Status.DONE : ImportJob.Status.FAILED;
        } catch (Throwable t) { // NOPMD - inclui Error de propósito: o job nunca fica preso em RUNNING
            // Só a classe: a mensagem pode carregar conteúdo. Auditoria, armazenamento ou algo pior falhou.
            LOG.error("Importação {} interrompida: {}", state.id, t.getClass().getName());
        } finally {
            state.finish(result);
            permits.release();
            ImportJob job = state.snapshot();
            LOG.info(
                    "Importação {} terminou: status={} importadas={} ignoradas={} colisoes={} falhas={}",
                    job.id(),
                    job.status(),
                    job.imported(),
                    job.skipped(),
                    job.collisions(),
                    job.failed());
        }
    }

    /**
     * Percorre em largura. Devolve {@code true} só se a raiz foi baixada dentro do escopo e o job não foi cancelado
     * nem estourou o prazo.
     */
    private boolean crawl(Run run) {
        JobState state = run.state();
        URI root = state.root;
        Scope scope = Scope.of(root);
        long deadline = System.nanoTime() + properties.maxDuration().toNanos();
        Deque<Queued> queue = new ArrayDeque<>();
        Set<URI> seen = new HashSet<>();
        Set<String> producedIds = new HashSet<>();
        queue.add(new Queued(root, 0));
        seen.add(root);
        int visited = 0;
        boolean rootOk = false;
        while (!queue.isEmpty() && visited < properties.maxPages()) {
            if (state.cancelled || System.nanoTime() - deadline >= 0) {
                return false;
            }
            Queued current = queue.poll();
            visited++;
            Optional<FetchedPage> fetched = fetcher.fetch(current.uri(), run.domains());
            if (fetched.isEmpty()) {
                state.failed.incrementAndGet();
                continue;
            }
            FetchedPage page = fetched.get();
            Optional<URI> finalUrl = canonical(page.url())
                    .filter(scope::contains)
                    // Mesma regra da raiz para cada página: em domínio com estrutura de versão, só a versão pedida.
                    .filter(u -> !versionedUrls.hasRules(u) || versionedUrls.matches(u, state.language, state.version));
            if (finalUrl.isEmpty() || (!finalUrl.get().equals(current.uri()) && !seen.add(finalUrl.get()))) {
                state.skipped.incrementAndGet();
                continue;
            }
            if (current.depth() == 0) {
                rootOk = true;
            }
            org.jsoup.nodes.Document html = Jsoup.parse(page.html(), page.url().toString());
            handlePage(run, scope, finalUrl.get(), html, page.html(), producedIds);
            if (current.depth() < run.depth()) {
                for (URI link : links(html, scope)) {
                    if (seen.size() < properties.maxPages() && seen.add(link)) {
                        queue.add(new Queued(link, current.depth() + 1));
                    }
                }
            }
        }
        return rootOk;
    }

    private void handlePage(
            Run run, Scope scope, URI url, org.jsoup.nodes.Document html, String rawHtml, Set<String> producedIds) {
        JobState state = run.state();
        String markdown = converter.convert(rawHtml);
        if (markdown.isBlank() || !scanner.scan(markdown).isEmpty()) {
            state.skipped.incrementAndGet();
            return;
        }
        String slug = slugFor(scope.relativePath(url));
        DocumentId id;
        Document document;
        try {
            id = DocumentId.of("publico/" + state.language + "/" + state.version + "/" + slug + ".md");
            if (!producedIds.add(id.value())) {
                state.collisions.incrementAndGet();
                return;
            }
            if (store.find(id).isPresent()) {
                state.skipped.incrementAndGet();
                return;
            }
            document = parser.parse(
                    frontmatter(id, state, url, sectionFor(html, slug)) + markdown + "\n",
                    id,
                    DocumentDefaults.empty());
        } catch (InvalidDocumentException e) {
            state.failed.incrementAndGet();
            return;
        }
        recordUpload(run.actor(), state, document, "tentativa");
        boolean written;
        try {
            written = store.saveIfAbsent(document);
        } catch (Throwable t) { // NOPMD - o job precisa registrar o "falhou" até para Error
            try {
                if (t instanceof DocumentChangeNotPropagatedException) {
                    // Gravado; quem falhou foi um ouvinte (o índice). Conta como importada e o desfecho diz isso.
                    state.imported.incrementAndGet();
                    recordUpload(run.actor(), state, document, "salvo", Optional.of("indice"));
                } else {
                    recordUpload(run.actor(), state, document, "falhou", Optional.empty());
                }
            } catch (RuntimeException auditFailure) {
                t.addSuppressed(auditFailure);
            }
            throw t;
        }
        // O documento já está no acervo: conta antes de auditar, para que uma falha da auditoria (que ainda derruba o
        // job) não deixe a contagem mentir. "existente": alguém gravou o mesmo id entre a checagem e a gravação.
        if (written) {
            state.imported.incrementAndGet();
        } else {
            state.skipped.incrementAndGet();
        }
        // Mesma convenção do fallback web: tentativa antes de gravar, salvo|existente|falhou depois.
        recordUpload(run.actor(), state, document, written ? "salvo" : "existente");
    }

    private void recordUpload(String actor, JobState state, Document document, String result) {
        recordUpload(actor, state, document, result, Optional.empty());
    }

    private void recordUpload(
            String actor, JobState state, Document document, String result, Optional<String> warning) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("document", document.metadata().id().value());
        details.put("bytes", Integer.toString(document.body().getBytes(StandardCharsets.UTF_8).length));
        details.put("job", state.id);
        details.put("origem", "importacao");
        details.put("resultado", result);
        warning.ifPresent(w -> details.put("aviso", w));
        audit(AuditEventType.DOCUMENT_UPLOADED, actor, details);
    }

    private String frontmatter(DocumentId id, JobState state, URI url, String section) {
        StringBuilder out = new StringBuilder(DELIMITER).append('\n');
        line(out, "id", id.value());
        line(out, "fonte", "publico");
        line(out, "linguagem", state.language);
        line(out, "versao", state.version);
        line(out, "secao", section);
        line(out, "url_origem", url.toString());
        line(
                out,
                "coletado_em",
                clock.instant().atZone(clock.getZone()).toLocalDate().toString());
        out.append("revisado: false\n");
        return out.append(DELIMITER).append('\n').toString();
    }

    private static void line(StringBuilder out, String key, String value) {
        out.append(key)
                .append(": \"")
                .append(value.replace("\\", "\\\\").replace("\"", "\\\""))
                .append("\"\n");
    }

    /** Título da página se for limpo (sem segredo nem caractere de controle); senão o slug. */
    private String sectionFor(org.jsoup.nodes.Document html, String slug) {
        String title = html.title().strip();
        if (title.isEmpty()
                || title.length() > MAX_SECTION_CHARS
                || title.chars().anyMatch(ImportService::isControl)
                || !scanner.scan(title).isEmpty()) {
            return slug;
        }
        return title;
    }

    private static boolean isControl(int c) {
        return Character.isISOControl(c) || c == NEXT_LINE || c == LINE_SEPARATOR || c == PARAGRAPH_SEPARATOR;
    }

    // ---- links e escopo -------------------------------------------------------------------------------------------

    private List<URI> links(org.jsoup.nodes.Document html, Scope scope) {
        List<URI> out = new ArrayList<>();
        for (Element a : html.select("a[href]")) {
            String absolute = a.absUrl("href");
            if (absolute.isEmpty()) {
                continue;
            }
            try {
                URI uri = new URI(absolute);
                if (schemeAllowed(uri.getScheme())) {
                    canonical(uri).filter(scope::contains).ifPresent(out::add);
                }
            } catch (URISyntaxException e) {
                // link malformado: ignora
            }
        }
        return out;
    }

    /**
     * O que conta como "dentro da raiz": mesmo host e porta e caminho sob o prefixo. Raiz terminada em {@code /} usa o
     * próprio caminho; raiz cujo último trecho termina em {@code .html} ou {@code .htm} (qualquer caixa) é uma página,
     * e o prefixo é o diretório dela; qualquer outra raiz sem barra final ({@code /guia}, {@code /spring-boot/4.1}) é
     * um diretório: vale ela mesma e tudo sob {@code /guia/}. O limite é por segmento: {@code /guia-extra} não entra
     * no prefixo {@code /guia}.
     */
    private record Scope(String scheme, String host, int port, String prefix, String rootPath) {

        static Scope of(URI root) {
            String path = root.getPath();
            String prefix;
            if (path.endsWith("/")) {
                prefix = path;
            } else {
                String last = path.substring(path.lastIndexOf('/') + 1);
                prefix =
                        PAGE_EXTENSION.matcher(last).find() ? path.substring(0, path.lastIndexOf('/') + 1) : path + "/";
            }
            return new Scope(root.getScheme(), root.getHost(), effectivePort(root), prefix, path);
        }

        boolean contains(URI uri) {
            if (!scheme.equalsIgnoreCase(uri.getScheme())
                    || !host.equalsIgnoreCase(uri.getHost())
                    || port != effectivePort(uri)) {
                return false;
            }
            String path = uri.getPath() == null ? "" : uri.getPath();
            return path.equals(rootPath) || path.startsWith(prefix);
        }

        /** Caminho depois do prefixo; vazio para a própria raiz. */
        String relativePath(URI uri) {
            String path = uri.getPath() == null ? "" : uri.getPath();
            return path.startsWith(prefix) ? path.substring(prefix.length()) : "";
        }

        private static int effectivePort(URI uri) {
            if (uri.getPort() != -1) {
                return uri.getPort();
            }
            return HTTP.equalsIgnoreCase(uri.getScheme()) ? HTTP_PORT : HTTPS_PORT;
        }
    }

    /**
     * Slug do caminho relativo à raiz: tira {@code .html}/{@code .htm} do último trecho, troca {@code /} por
     * {@code -} e aplica as mesmas regras do upload (minúsculas, sem acento, só {@code [a-z0-9-]}, até 80
     * caracteres). Vazio vira "index".
     */
    static String slugFor(String relativePath) {
        String path = relativePath.endsWith("/") ? relativePath.substring(0, relativePath.length() - 1) : relativePath;
        String stripped = PAGE_EXTENSION.matcher(path).replaceFirst("");
        String ascii = DIACRITICS
                .matcher(Normalizer.normalize(stripped, Normalizer.Form.NFD))
                .replaceAll("");
        String slug = EDGE_DASHES
                .matcher(NON_SLUG.matcher(ascii.toLowerCase(Locale.ROOT)).replaceAll("-"))
                .replaceAll("");
        if (slug.length() > MAX_SLUG_CHARS) {
            slug = EDGE_DASHES.matcher(slug.substring(0, MAX_SLUG_CHARS)).replaceAll("");
        }
        return slug.isEmpty() ? DEFAULT_SLUG : slug;
    }
}
