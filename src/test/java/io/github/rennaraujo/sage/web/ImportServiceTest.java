package io.github.rennaraujo.sage.web;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.github.rennaraujo.sage.audit.AuditEvent;
import io.github.rennaraujo.sage.audit.AuditEventType;
import io.github.rennaraujo.sage.audit.AuditException;
import io.github.rennaraujo.sage.audit.AuditLog;
import io.github.rennaraujo.sage.auth.RateLimitProperties;
import io.github.rennaraujo.sage.auth.RateLimiter;
import io.github.rennaraujo.sage.document.Document;
import io.github.rennaraujo.sage.document.DocumentDefaults;
import io.github.rennaraujo.sage.document.DocumentId;
import io.github.rennaraujo.sage.document.DocumentMetadata;
import io.github.rennaraujo.sage.document.DocumentStore;
import io.github.rennaraujo.sage.document.FrontmatterParser;
import io.github.rennaraujo.sage.document.InvalidDocumentException;
import io.github.rennaraujo.sage.shared.RateLimitExceededException;
import io.github.rennaraujo.sage.upload.SecretScanner;
import java.net.InetAddress;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

class ImportServiceTest {

    private static final String HOST = "docs.example.test";
    private static final Set<String> DOMAINS = Set.of("example.test");
    private static final String ACTOR = "editor-teste";
    private static final Duration WAIT = Duration.ofSeconds(10);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-27T12:00:00Z"), ZoneOffset.UTC);

    private static WireMockServer wire;

    private final MemoryStore store = new MemoryStore();
    private final List<AuditEvent> audit = new CopyOnWriteArrayList<>();
    private final List<ImportService> createdServices = new ArrayList<>();

    @BeforeAll
    static void start() {
        wire = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wire.start();
    }

    @AfterAll
    static void stop() {
        wire.stop();
    }

    @AfterEach
    void shutdownServices() {
        createdServices.forEach(ImportService::shutdown);
    }

    @BeforeEach
    void reset() {
        wire.resetAll();
    }

    private ImportService service(int maxDepth, int maxPages) {
        return service(maxDepth, maxPages, audit::add, 1000);
    }

    private ImportService service(int maxDepth, int maxPages, AuditLog auditLog, int uploadsPerMinute) {
        return service(
                new ImportProperties(maxDepth, maxPages, 3, 2, Duration.ofMinutes(1)), auditLog, uploadsPerMinute);
    }

    private ImportService service(ImportProperties props, AuditLog auditLog, int uploadsPerMinute) {
        WebProperties web = new WebProperties(
                Map.of("java", DOMAINS),
                3,
                Duration.ofSeconds(2),
                DataSize.ofKilobytes(256),
                3,
                "",
                URI.create("https://api.search.brave.com/res/v1/web/search"));
        AllowlistedPageFetcher fetcher =
                new AllowlistedPageFetcher(web, h -> new InetAddress[] {InetAddress.getLoopbackAddress()}, true);
        ImportService created = new ImportService(
                fetcher,
                new HtmlToMarkdown(),
                store,
                new FrontmatterParser(CLOCK),
                new SecretScanner(),
                auditLog,
                new RateLimiter(new RateLimitProperties(20, uploadsPerMinute)),
                web,
                props,
                CLOCK,
                true);
        createdServices.add(created);
        return created;
    }

    private URI url(String path) {
        return URI.create("http://" + HOST + ":" + wire.port() + path);
    }

    private void page(String path, String body, String... links) {
        StringBuilder html = new StringBuilder("<html><head><title>Página ")
                .append(path)
                .append("</title></head><body><h1>Título</h1><p>")
                .append(body)
                .append("</p>");
        for (String link : links) {
            html.append("<a href=\"").append(link).append("\">link</a>");
        }
        wire.stubFor(get(urlEqualTo(path))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "text/html; charset=UTF-8")
                        .withBody(html.append("</body></html>").toString())));
    }

    private ImportJob await(ImportService service, String id) {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            ImportJob job = service.status(id).orElseThrow();
            if (job.status() != ImportJob.Status.RUNNING) {
                return job;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("importação não terminou a tempo");
    }

    private void requested(String path, int times) {
        wire.verify(times, getRequestedFor(urlEqualTo(path)));
    }

    @Test
    void importsOnlyWithinRootPrefix() {
        page(
                "/docs/guide/",
                "raiz",
                "a.html",
                "/docs/other/x.html",
                "/docs/guide-extra/y.html",
                "../up.html",
                "https://evil.test/z.html",
                "mailto:a@b.test",
                "a.html#secao");
        page("/docs/guide/a.html", "pagina a");
        page("/docs/other/x.html", "fora");
        page("/docs/guide-extra/y.html", "irmã");
        page("/docs/up.html", "acima");

        ImportService service = service(2, 50);
        ImportJob job = await(
                service,
                service.start(url("/docs/guide/"), "java", "25", 2, ACTOR).id());

        assertThat(job.status()).isEqualTo(ImportJob.Status.DONE);
        assertThat(job.imported()).isEqualTo(2);
        assertThat(store.ids()).containsExactlyInAnyOrder("publico/java/25/index.md", "publico/java/25/a.md");
        requested("/docs/other/x.html", 0);
        requested("/docs/guide-extra/y.html", 0);
        requested("/docs/up.html", 0);
        requested("/docs/guide/a.html", 1);
    }

    @Test
    void savesPagesUnreviewedWithOriginAndMetadata() {
        page("/docs/guide/", "conteudo da raiz");

        ImportService service = service(0, 10);
        await(
                service,
                service.start(url("/docs/guide/"), "Java", "25", 0, ACTOR).id());

        Document saved = store.find(DocumentId.of("publico/java/25/index.md")).orElseThrow();
        DocumentMetadata meta = saved.metadata();
        assertThat(meta.reviewed()).isFalse();
        assertThat(meta.language()).isEqualTo("java");
        assertThat(meta.version()).contains("25");
        assertThat(meta.originUrl()).contains(url("/docs/guide/"));
        assertThat(meta.collectedAt()).isEqualTo(LocalDate.of(2026, 10, 27));
        assertThat(meta.section()).isEqualTo("Página /docs/guide/");
        assertThat(saved.body()).contains("conteudo da raiz");
    }

    @Test
    void respectsDepth() {
        page("/docs/guide/", "raiz", "a.html");
        page("/docs/guide/a.html", "a", "b.html");
        page("/docs/guide/b.html", "b", "c.html");
        page("/docs/guide/c.html", "c");

        ImportService service = service(2, 50);
        ImportJob job = await(
                service,
                service.start(url("/docs/guide/"), "java", "25", 1, ACTOR).id());

        assertThat(job.imported()).isEqualTo(2);
        requested("/docs/guide/b.html", 0);
        requested("/docs/guide/c.html", 0);
    }

    @Test
    void respectsMaxPages() {
        page("/docs/guide/", "raiz", "1.html", "2.html", "3.html", "4.html");
        for (int i = 1; i <= 4; i++) {
            page("/docs/guide/" + i + ".html", "p" + i);
        }

        ImportService service = service(2, 3);
        ImportJob job = await(
                service,
                service.start(url("/docs/guide/"), "java", "25", 2, ACTOR).id());

        assertThat(job.status()).isEqualTo(ImportJob.Status.DONE);
        assertThat(job.imported()).isEqualTo(3);
        assertThat(wire.getAllServeEvents()).hasSize(3);
    }

    @Test
    void statusReportsCounts() {
        String key = "AKIA" + "IOSFODNN7EXAMPLE";
        page("/docs/guide/", "raiz", "ok.html", "segredo.html", "quebrada.html", "existente.html", "ok.html?x=1");
        page("/docs/guide/ok.html", "bom");
        page("/docs/guide/segredo.html", "chave " + key);
        wire.stubFor(get(urlEqualTo("/docs/guide/quebrada.html"))
                .willReturn(aResponse().withStatus(404)));
        page("/docs/guide/existente.html", "ja tinha");
        store.save(new Document(
                new FrontmatterParser(CLOCK)
                        .parse(
                                "---\nid: publico/java/25/existente.md\nlinguagem: java\nversao: \"25\"\nsecao: x\n"
                                        + "url_origem: https://docs.example.test/x\n---\noriginal\n",
                                DocumentDefaults.empty())
                        .metadata(),
                "original\n"));

        ImportService service = service(1, 50);
        String id = service.start(url("/docs/guide/"), "java", "25", 1, ACTOR).id();
        ImportJob job = await(service, id);

        assertThat(job.status()).isEqualTo(ImportJob.Status.DONE);
        assertThat(job.imported()).isEqualTo(2); // raiz e ok.html (a variante com query é a mesma página)
        assertThat(job.skipped()).isEqualTo(2); // segredo e a que já existia
        assertThat(job.failed()).isEqualTo(1); // 404
        assertThat(store.find(DocumentId.of("publico/java/25/existente.md"))
                        .orElseThrow()
                        .body())
                .isEqualTo("original\n");
        assertThat(store.ids()).doesNotContain("publico/java/25/segredo.md");
        assertThat(job.toString()).doesNotContain(key);
        requested("/docs/guide/ok.html", 1);
    }

    @Test
    void unknownJobIsEmpty() {
        assertThat(service(2, 10).status("nao-existe")).isEmpty();
    }

    @Test
    void importIsAudited() {
        page("/docs/guide/", "raiz", "a.html");
        page("/docs/guide/a.html", "a");

        ImportService service = service(1, 10);
        ImportJob job = await(
                service,
                service.start(url("/docs/guide/"), "java", "25", 1, ACTOR).id());

        List<AuditEvent> events = new ArrayList<>(audit);
        assertThat(events.getFirst().type()).isEqualTo(AuditEventType.IMPORT_STARTED);
        assertThat(events.getFirst().actor()).isEqualTo(ACTOR);
        assertThat(events.getFirst().details()).containsEntry("job", job.id()).containsEntry("host", HOST);
        assertThat(events.stream()
                        .filter(e -> e.type() == AuditEventType.DOCUMENT_UPLOADED
                                && "tentativa".equals(e.details().get("resultado"))))
                .extracting(e -> e.details().get("document"))
                .containsExactlyInAnyOrder("publico/java/25/index.md", "publico/java/25/a.md");
        assertThat(events.stream()
                        .filter(e -> e.type() == AuditEventType.DOCUMENT_UPLOADED
                                && "salvo".equals(e.details().get("resultado"))))
                .hasSize(2);
        assertThat(events.toString()).doesNotContain("raiz");
    }

    @Test
    void startAuditFailureCreatesNoJobAndFetchesNothing() {
        page("/docs/guide/", "raiz");
        ImportService service = service(
                1,
                10,
                e -> {
                    throw new AuditException();
                },
                1000);

        assertThatThrownBy(() -> service.start(url("/docs/guide/"), "java", "25", 1, ACTOR))
                .isInstanceOf(AuditException.class);

        assertThat(wire.getAllServeEvents()).isEmpty();
        assertThat(store.ids()).isEmpty();
    }

    private static boolean outcome(AuditEvent e, String result) {
        return e.type() == AuditEventType.DOCUMENT_UPLOADED
                && result.equals(e.details().get("resultado"));
    }

    @Test
    void attemptAuditFailureSavesNothing() {
        page("/docs/guide/", "raiz");
        ImportService service = service(
                0,
                10,
                e -> {
                    if (outcome(e, "tentativa")) {
                        throw new AuditException();
                    }
                },
                1000);

        ImportJob job = await(
                service,
                service.start(url("/docs/guide/"), "java", "25", 0, ACTOR).id());

        assertThat(job.status()).isEqualTo(ImportJob.Status.FAILED);
        assertThat(job.imported()).isZero();
        assertThat(store.ids()).isEmpty();
    }

    @Test
    void storeFailureRecordsFalhouAndFailsTheJob() {
        page("/docs/guide/", "raiz");
        store.failure = new AssertionError("disco");
        ImportService service = service(0, 10);

        ImportJob job = await(
                service,
                service.start(url("/docs/guide/"), "java", "25", 0, ACTOR).id());

        assertThat(job.status()).isEqualTo(ImportJob.Status.FAILED);
        assertThat(audit.stream().filter(e -> outcome(e, "tentativa"))).hasSize(1);
        assertThat(audit.stream().filter(e -> outcome(e, "falhou"))).hasSize(1);
    }

    @Test
    void outcomeAuditFailureStillCountsTheSavedDocument() {
        page("/docs/guide/", "raiz");
        ImportService service = service(
                0,
                10,
                e -> {
                    if (outcome(e, "salvo")) {
                        throw new AuditException();
                    }
                },
                1000);

        ImportJob job = await(
                service,
                service.start(url("/docs/guide/"), "java", "25", 0, ACTOR).id());

        assertThat(job.status()).isEqualTo(ImportJob.Status.FAILED);
        assertThat(store.ids()).containsExactly("publico/java/25/index.md");
        assertThat(job.imported()).isEqualTo(1);
    }

    @Test
    void pageAuditFailureStopsTheJobBeforeSaving() {
        page("/docs/guide/", "raiz");
        ImportService service = service(
                0,
                10,
                e -> {
                    if (e.type() == AuditEventType.DOCUMENT_UPLOADED) {
                        throw new AuditException();
                    }
                },
                1000);

        ImportJob job = await(
                service,
                service.start(url("/docs/guide/"), "java", "25", 0, ACTOR).id());

        assertThat(job.status()).isEqualTo(ImportJob.Status.FAILED);
        assertThat(store.ids()).isEmpty();
    }

    @Test
    void unreachableRootFailsTheJob() {
        wire.stubFor(get(urlEqualTo("/docs/guide/")).willReturn(aResponse().withStatus(500)));

        ImportService service = service(1, 10);
        ImportJob job = await(
                service,
                service.start(url("/docs/guide/"), "java", "25", 1, ACTOR).id());

        assertThat(job.status()).isEqualTo(ImportJob.Status.FAILED);
        assertThat(job.failed()).isEqualTo(1);
    }

    @Test
    void rejectsRootsOutsideTheAllowlistWithoutEchoingThem() {
        ImportService service = service(1, 10);

        for (String root : List.of(
                "https://evil.test/docs/guide/",
                "https://docs.example.test.evil.test/docs/",
                "https://alguem:p4ss-ZZ9@docs.example.test/docs/",
                "ftp://docs.example.test/docs/",
                "/docs/relativa")) {
            assertThatThrownBy(() -> service.start(URI.create(root), "java", "25", 1, ACTOR))
                    .isInstanceOf(InvalidDocumentException.class)
                    .satisfies(e ->
                            assertThat(e.getMessage()).doesNotContain("evil").doesNotContain("p4ss-ZZ9"));
        }
        assertThat(audit).isEmpty();
    }

    @Test
    void rejectsUnknownLanguageBadDepthAndBadVersion() {
        ImportService service = service(2, 10);
        URI root = url("/docs/guide/");

        assertThatThrownBy(() -> service.start(root, "rust", "1", 1, ACTOR))
                .isInstanceOf(InvalidDocumentException.class);
        assertThatThrownBy(() -> service.start(root, "java", "25", 3, ACTOR))
                .isInstanceOf(InvalidDocumentException.class);
        assertThatThrownBy(() -> service.start(root, "java", "25", -1, ACTOR))
                .isInstanceOf(InvalidDocumentException.class);
        assertThatThrownBy(() -> service.start(root, "java", "25/../x", 1, ACTOR))
                .isInstanceOf(InvalidDocumentException.class);
        assertThat(audit).isEmpty();
    }

    @Test
    void consumesTheUploadRateLimitOncePerStart() {
        page("/docs/guide/", "raiz");
        ImportService service = service(0, 10, audit::add, 1);

        service.start(url("/docs/guide/"), "java", "25", 0, ACTOR);

        assertThatThrownBy(() -> service.start(url("/docs/guide/"), "java", "25", 0, ACTOR))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void keepsOnlyTheLatestJobs() {
        page("/docs/guide/", "raiz");
        ImportService service = service(0, 10);

        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String id =
                    service.start(url("/docs/guide/"), "java", "25", 0, ACTOR).id();
            await(service, id);
            ids.add(id);
        }

        assertThat(service.status(ids.get(0))).isEmpty();
        assertThat(service.status(ids.get(3))).isPresent();
    }

    private void slowPage(String path, int delayMillis, String... links) {
        StringBuilder html = new StringBuilder("<html><head><title>Lenta</title></head><body><p>lenta</p>");
        for (String link : links) {
            html.append("<a href=\"").append(link).append("\">l</a>");
        }
        wire.stubFor(get(urlEqualTo(path))
                .willReturn(aResponse()
                        .withFixedDelay(delayMillis)
                        .withHeader("Content-Type", "text/html; charset=UTF-8")
                        .withBody(html.append("</body></html>").toString())));
    }

    private ImportProperties limits(int jobsKept, int maxConcurrent, Duration maxDuration) {
        return new ImportProperties(2, 50, jobsKept, maxConcurrent, maxDuration);
    }

    @Test
    void extensionlessRootWithADottedLastSegmentIsADirectory() {
        page("/docs/spring-boot/4.1", "raiz", "/docs/spring-boot/4.1/a.html", "/docs/spring-boot/4.0/b.html");
        page("/docs/spring-boot/4.1/a.html", "a");
        page("/docs/spring-boot/4.0/b.html", "outra versao");

        ImportService service = service(2, 50);
        ImportJob job = await(
                service,
                service.start(url("/docs/spring-boot/4.1"), "java", "4.1", 2, ACTOR)
                        .id());

        assertThat(job.imported()).isEqualTo(2);
        assertThat(store.ids()).containsExactlyInAnyOrder("publico/java/4.1/index.md", "publico/java/4.1/a.md");
        requested("/docs/spring-boot/4.0/b.html", 0);
    }

    @Test
    void htmlRootUsesItsDirectoryAsPrefix() {
        page("/docs/guide/index.html", "raiz", "/docs/guide/other.html", "/docs/elsewhere.html");
        page("/docs/guide/other.html", "outra");
        page("/docs/elsewhere.html", "fora");

        ImportService service = service(2, 50);
        ImportJob ok = await(
                service,
                service.start(url("/docs/guide/index.html"), "java", "25", 2, ACTOR)
                        .id());

        assertThat(ok.imported()).isEqualTo(2);
        requested("/docs/elsewhere.html", 0);
    }

    @Test
    void rootRedirectedOutOfScopeFailsTheJob() {
        wire.stubFor(get(urlEqualTo("/docs/guide/"))
                .willReturn(aResponse().withStatus(302).withHeader("Location", "/docs/other/x.html")));
        page("/docs/other/x.html", "fora");

        ImportService service = service(1, 10);
        ImportJob job = await(
                service,
                service.start(url("/docs/guide/"), "java", "25", 1, ACTOR).id());

        assertThat(job.status()).isEqualTo(ImportJob.Status.FAILED);
        assertThat(job.imported()).isZero();
        assertThat(store.ids()).isEmpty();
    }

    @Test
    void pageRedirectedOutOfScopeIsSkippedNotSaved() {
        page("/docs/guide/", "raiz", "r.html");
        wire.stubFor(get(urlEqualTo("/docs/guide/r.html"))
                .willReturn(aResponse().withStatus(302).withHeader("Location", "/docs/other/x.html")));
        page("/docs/other/x.html", "fora");

        ImportService service = service(1, 10);
        ImportJob job = await(
                service,
                service.start(url("/docs/guide/"), "java", "25", 1, ACTOR).id());

        assertThat(job.status()).isEqualTo(ImportJob.Status.DONE);
        assertThat(job.imported()).isEqualTo(1);
        assertThat(job.skipped()).isEqualTo(1);
        assertThat(store.ids()).containsExactly("publico/java/25/index.md");
    }

    @Test
    void encodedDotsAndSlashesInLinksAreNeverFollowed() {
        page(
                "/docs/guide/",
                "raiz",
                "/docs/guide/%2e%2e/secret.html",
                "/docs/guide/a%2fb.html",
                "%2E%2E/%2e%2e/x.html");

        ImportService service = service(1, 10);
        ImportJob job = await(
                service,
                service.start(url("/docs/guide/"), "java", "25", 1, ACTOR).id());

        assertThat(job.imported()).isEqualTo(1);
        assertThat(wire.getAllServeEvents()).hasSize(1);
    }

    @Test
    void baseHrefIsHonoured() {
        wire.stubFor(get(urlEqualTo("/docs/guide/"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "text/html; charset=UTF-8")
                        .withBody("<html><head><title>R</title><base href=\"/docs/guide/sub/\"></head>"
                                + "<body><p>raiz</p><a href=\"p.html\">p</a></body></html>")));
        page("/docs/guide/sub/p.html", "sub");
        page("/docs/guide/p.html", "sem base");

        ImportService service = service(1, 10);
        ImportJob job = await(
                service,
                service.start(url("/docs/guide/"), "java", "25", 1, ACTOR).id());

        assertThat(job.imported()).isEqualTo(2);
        assertThat(store.ids()).contains("publico/java/25/sub-p.md");
        requested("/docs/guide/p.html", 0);
    }

    @Test
    void slugCollisionsAreCountedApartFromExistingDocuments() {
        page("/docs/guide/", "raiz", "a/b.html", "a-b.html");
        page("/docs/guide/a/b.html", "primeira");
        page("/docs/guide/a-b.html", "segunda");

        ImportService service = service(1, 10);
        ImportJob job = await(
                service,
                service.start(url("/docs/guide/"), "java", "25", 1, ACTOR).id());

        assertThat(job.imported()).isEqualTo(2);
        assertThat(job.collisions()).isEqualTo(1);
        assertThat(job.skipped()).isZero();
    }

    @Test
    void runningJobsAreNeverEvictedAndAFullRegistryRefusesWith429() {
        slowPage("/docs/guide/", 1500);
        ImportService service = service(limits(2, 3, Duration.ofMinutes(1)), audit::add, 1000);
        String first =
                service.start(url("/docs/guide/"), "java", "25", 0, ACTOR).id();
        String second =
                service.start(url("/docs/guide/"), "java", "26", 0, ACTOR).id();

        assertThatThrownBy(() -> service.start(url("/docs/guide/"), "java", "27", 0, ACTOR))
                .isInstanceOf(RateLimitExceededException.class);

        assertThat(service.status(first).orElseThrow().status()).isEqualTo(ImportJob.Status.RUNNING);
        assertThat(service.status(second).orElseThrow().status()).isEqualTo(ImportJob.Status.RUNNING);
        assertThat(audit.stream().filter(e -> e.type() == AuditEventType.IMPORT_STARTED))
                .hasSize(2);
    }

    @Test
    void finishedJobsAreEvictedBeforeRunningOnes() {
        page("/docs/fast/", "rapida");
        slowPage("/docs/slow/", 1200);
        ImportService service = service(limits(2, 3, Duration.ofMinutes(1)), audit::add, 1000);
        String done = service.start(url("/docs/fast/"), "java", "25", 0, ACTOR).id();
        await(service, done);
        String running =
                service.start(url("/docs/slow/"), "java", "25", 0, ACTOR).id();

        String third = service.start(url("/docs/fast/"), "java", "26", 0, ACTOR).id();

        assertThat(service.status(done)).isEmpty();
        assertThat(service.status(running)).isPresent();
        assertThat(service.status(third)).isPresent();
    }

    @Test
    void concurrentJobLimitRefusesWith429AndReleasesThePermit() {
        slowPage("/docs/guide/", 600);
        ImportService service = service(limits(10, 1, Duration.ofMinutes(1)), audit::add, 1000);
        String first =
                service.start(url("/docs/guide/"), "java", "25", 0, ACTOR).id();

        assertThatThrownBy(() -> service.start(url("/docs/guide/"), "java", "26", 0, ACTOR))
                .isInstanceOf(RateLimitExceededException.class);
        assertThat(audit.stream().filter(e -> e.type() == AuditEventType.IMPORT_STARTED))
                .hasSize(1);

        await(service, first);
        String again =
                service.start(url("/docs/guide/"), "java", "27", 0, ACTOR).id();
        assertThat(await(service, again).status()).isEqualTo(ImportJob.Status.DONE);
    }

    @Test
    void auditFailureAtStartReleasesThePermit() {
        page("/docs/guide/", "raiz");
        boolean[] fail = {true};
        ImportService service = service(
                limits(10, 1, Duration.ofMinutes(1)),
                e -> {
                    if (fail[0]) {
                        throw new AuditException();
                    }
                },
                1000);

        assertThatThrownBy(() -> service.start(url("/docs/guide/"), "java", "25", 0, ACTOR))
                .isInstanceOf(AuditException.class);
        fail[0] = false;

        assertThat(await(
                                service,
                                service.start(url("/docs/guide/"), "java", "25", 0, ACTOR)
                                        .id())
                        .status())
                .isEqualTo(ImportJob.Status.DONE);
    }

    @Test
    void jobPastItsDeadlineEndsFailedKeepingTheCounts() {
        page("/docs/guide/", "raiz", "p1.html", "p2.html", "p3.html", "p4.html", "p5.html");
        for (int i = 1; i <= 5; i++) {
            slowPage("/docs/guide/p" + i + ".html", 250);
        }
        ImportService service = service(limits(10, 2, Duration.ofMillis(400)), audit::add, 1000);

        ImportJob job = await(
                service,
                service.start(url("/docs/guide/"), "java", "25", 1, ACTOR).id());

        assertThat(job.status()).isEqualTo(ImportJob.Status.FAILED);
        assertThat(job.imported()).isBetween(1, 4);
    }

    @Test
    void anErrorInTheWorkerStillEndsInFailedAndFreesThePermit() {
        page("/docs/guide/", "raiz");
        ImportService service = service(limits(10, 1, Duration.ofMinutes(1)), audit::add, 1000);
        store.failure = new StackOverflowError();

        ImportJob job = await(
                service,
                service.start(url("/docs/guide/"), "java", "25", 0, ACTOR).id());

        assertThat(job.status()).isEqualTo(ImportJob.Status.FAILED);
        store.failure = null;
        assertThat(await(
                                service,
                                service.start(url("/docs/guide/"), "java", "26", 0, ACTOR)
                                        .id())
                        .status())
                .isEqualTo(ImportJob.Status.DONE);
    }

    @Test
    void shutdownCancelsRunningJobsAndMarksThemFailed() {
        slowPage("/docs/guide/", 700, "a.html");
        page("/docs/guide/a.html", "a");
        ImportService service = service(2, 10);
        String id = service.start(url("/docs/guide/"), "java", "25", 1, ACTOR).id();

        service.shutdown();

        ImportJob job = service.status(id).orElseThrow();
        assertThat(job.status()).isEqualTo(ImportJob.Status.FAILED);
        requested("/docs/guide/a.html", 0);
    }

    private static final class MemoryStore implements DocumentStore {
        private final Map<String, Document> documents = new ConcurrentHashMap<>();

        volatile Error failure;

        @Override
        public void save(Document doc) {
            documents.put(doc.metadata().id().value(), doc);
        }

        @Override
        public boolean saveIfAbsent(Document doc) {
            if (failure != null) {
                throw failure;
            }
            return documents.putIfAbsent(doc.metadata().id().value(), doc) == null;
        }

        @Override
        public Optional<Document> find(DocumentId id) {
            return Optional.ofNullable(documents.get(id.value()));
        }

        @Override
        public List<DocumentMetadata> listMetadata() {
            return documents.values().stream().map(Document::metadata).toList();
        }

        @Override
        public void delete(DocumentId id) {
            documents.remove(id.value());
        }

        @Override
        public Stream<Document> loadAll() {
            return documents.values().stream();
        }

        Set<String> ids() {
            return Set.copyOf(documents.keySet());
        }
    }
}
