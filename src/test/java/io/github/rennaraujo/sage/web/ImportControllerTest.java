package io.github.rennaraujo.sage.web;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.github.rennaraujo.sage.audit.AuditEvent;
import io.github.rennaraujo.sage.audit.AuditEventType;
import io.github.rennaraujo.sage.audit.AuditException;
import io.github.rennaraujo.sage.audit.AuditLog;
import io.github.rennaraujo.sage.auth.RateLimiter;
import io.github.rennaraujo.sage.document.DocumentId;
import io.github.rennaraujo.sage.document.DocumentStore;
import io.github.rennaraujo.sage.document.FrontmatterParser;
import io.github.rennaraujo.sage.upload.SecretScanner;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(webEnvironment = RANDOM_PORT, properties = "sage.models.dir=target/sem-modelos")
@AutoConfigureTestRestTemplate
class ImportControllerTest {

    private static final String LANGUAGE = "ctrltest";
    private static final String HOST = "docs.example.test";
    private static final int MASTER_KEY_BYTES = 32;
    private static final String EDITOR_NAME = "editor-teste";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String READER_KEY = newKey();
    private static final String EDITOR_KEY = newKey();
    private static final String OTHER_EDITOR_KEY = newKey();
    private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");

    private static WireMockServer wire;

    @TempDir
    static Path storageDir;

    @BeforeAll
    static void startWire() {
        wire = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wire.start();
    }

    @AfterAll
    static void stopWire() {
        wire.stop();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        byte[] master = new byte[MASTER_KEY_BYTES];
        RANDOM.nextBytes(master);
        Path keyFile = Files.createTempFile("sage-test-master", ".key");
        keyFile.toFile().deleteOnExit();
        Files.writeString(keyFile, Base64.getEncoder().encodeToString(master));
        registry.add("sage.crypto.provider", () -> "local");
        registry.add("sage.crypto.local-key-file", keyFile::toString);
        registry.add("sage.storage.type", () -> "local");
        registry.add("sage.storage.local-dir", storageDir::toString);
        registry.add("sage.auth.keys[0].name", () -> "leitor-teste");
        registry.add("sage.auth.keys[0].role", () -> "READER");
        registry.add("sage.auth.keys[0].sha256", () -> sha256Hex(READER_KEY));
        registry.add("sage.auth.keys[1].name", () -> EDITOR_NAME);
        registry.add("sage.auth.keys[1].role", () -> "EDITOR");
        registry.add("sage.auth.keys[1].sha256", () -> sha256Hex(EDITOR_KEY));
        registry.add("sage.auth.keys[2].name", () -> "outro-editor");
        registry.add("sage.auth.keys[2].role", () -> "EDITOR");
        registry.add("sage.auth.keys[2].sha256", () -> sha256Hex(OTHER_EDITOR_KEY));
        registry.add("sage.rate-limit.uploads-per-minute", () -> 1000);
        registry.add("sage.web.allowlist." + LANGUAGE, () -> "example.test");
    }

    /** O fetcher e o serviço de teste aceitam http e endereços locais, como nos testes do fetcher. */
    @TestConfiguration(proxyBeanMethods = false)
    static class TestImport {
        @Bean
        @Primary
        ImportService testImportService(
                DocumentStore store,
                FrontmatterParser parser,
                AuditLog auditLog,
                RateLimiter rateLimiter,
                WebProperties web,
                ImportProperties properties) {
            AllowlistedPageFetcher fetcher =
                    new AllowlistedPageFetcher(web, h -> new InetAddress[] {InetAddress.getLoopbackAddress()}, true);
            return new ImportService(
                    fetcher,
                    new HtmlToMarkdown(),
                    store,
                    parser,
                    new SecretScanner(),
                    auditLog,
                    rateLimiter,
                    web,
                    properties,
                    Clock.systemUTC(),
                    true);
        }
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    DocumentStore store;

    @MockitoBean
    AuditLog auditLog;

    @BeforeEach
    void clean() {
        wire.resetAll();
        store.listMetadata().forEach(m -> store.delete(m.id()));
    }

    private String root(String path) {
        return "http://" + HOST + ":" + wire.port() + path;
    }

    private void page(String path, String body, String... links) {
        StringBuilder html = new StringBuilder("<html><head><title>T</title></head><body><p>")
                .append(body)
                .append("</p>");
        for (String link : links) {
            html.append("<a href=\"").append(link).append("\">l</a>");
        }
        wire.stubFor(get(urlEqualTo(path))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "text/html; charset=UTF-8")
                        .withBody(html.append("</body></html>").toString())));
    }

    private ResponseEntity<String> post(String key, String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (key != null) {
            headers.setBearerAuth(key);
        }
        return rest.exchange("/api/importar", HttpMethod.POST, new HttpEntity<>(json, headers), String.class);
    }

    private ResponseEntity<String> getAs(String key, String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(key);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private static String body(String url, String depth) {
        return "{\"url\":\"" + url + "\",\"linguagem\":\"" + LANGUAGE + "\",\"versao\":\"25\""
                + (depth == null ? "" : ",\"profundidade\":" + depth) + "}";
    }

    private String awaitDone(String key, String id) throws InterruptedException {
        for (int i = 0; i < 500; i++) {
            String json = getAs(key, "/api/importar/" + id).getBody();
            if (json != null && !json.contains("\"RUNNING\"")) {
                return json;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("importação não terminou");
    }

    private static String idOf(ResponseEntity<String> response) {
        Matcher m = ID.matcher(response.getBody());
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    @Test
    void startsAnImportAndReportsStatus() throws InterruptedException {
        page("/docs/guide/", "raiz", "a.html");
        page("/docs/guide/a.html", "a");

        ResponseEntity<String> started = post(EDITOR_KEY, body(root("/docs/guide/"), "1"));

        assertThat(started.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String id = idOf(started);
        assertThat(id).hasSize(36);
        String status = awaitDone(EDITOR_KEY, id);
        assertThat(status)
                .contains("\"id\":\"" + id + "\"")
                .contains("\"status\":\"DONE\"")
                .contains("\"importadas\":2")
                .contains("\"ignoradas\":0")
                .contains("\"colisoes\":0")
                .contains("\"falhas\":0");
        assertThat(store.find(DocumentId.of("publico/" + LANGUAGE + "/25/a.md")))
                .isPresent();
    }

    @Test
    void depthDefaultsToTheConfiguredMaximum() throws InterruptedException {
        page("/docs/guide/", "raiz", "a.html");
        page("/docs/guide/a.html", "a", "b.html");
        page("/docs/guide/b.html", "b", "c.html");
        page("/docs/guide/c.html", "c");

        String id = idOf(post(EDITOR_KEY, body(root("/docs/guide/"), null)));

        assertThat(awaitDone(EDITOR_KEY, id)).contains("\"importadas\":3");
    }

    @Test
    void anyEditorCanReadAnyJob() throws InterruptedException {
        page("/docs/guide/", "raiz");
        String id = idOf(post(EDITOR_KEY, body(root("/docs/guide/"), "0")));

        assertThat(awaitDone(OTHER_EDITOR_KEY, id)).contains("\"status\":\"DONE\"");
    }

    @Test
    void nonAllowlistedRootIs422() {
        ResponseEntity<String> response = post(EDITOR_KEY, body("https://evil.test/docs/?token=abc123", "1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody()).doesNotContain("evil").doesNotContain("abc123");
    }

    @Test
    void malformedUrlIs422() {
        ResponseEntity<String> response = post(EDITOR_KEY, body("not a url at all", "1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.getBody()).doesNotContain("not a url");
    }

    @Test
    void depthAboveTheMaximumIs422() {
        ResponseEntity<String> response = post(EDITOR_KEY, body(root("/docs/guide/"), "3"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void blankFieldsAre400() {
        ResponseEntity<String> response = post(EDITOR_KEY, "{\"url\":\"\",\"linguagem\":\"\",\"versao\":\"\"}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void readerGets403() {
        ResponseEntity<String> post = post(READER_KEY, body(root("/docs/guide/"), "1"));
        ResponseEntity<String> status = getAs(READER_KEY, "/api/importar/qualquer");

        assertThat(post.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(wire.getAllServeEvents()).isEmpty();
    }

    @Test
    void anonymousGets401() {
        assertThat(post(null, body(root("/docs/guide/"), "1")).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void unknownJobIs404() {
        ResponseEntity<String> response = getAs(EDITOR_KEY, "/api/importar/00000000-0000-0000-0000-000000000000");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void importIsAudited() throws InterruptedException {
        page("/docs/guide/", "raiz");
        String id = idOf(post(EDITOR_KEY, body(root("/docs/guide/"), "0")));
        awaitDone(EDITOR_KEY, id);

        ArgumentCaptor<AuditEvent> events = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditLog, org.mockito.Mockito.atLeast(2)).record(events.capture());
        List<AuditEvent> all = events.getAllValues();
        assertThat(all.getFirst().type()).isEqualTo(AuditEventType.IMPORT_STARTED);
        assertThat(all.getFirst().actor()).isEqualTo(EDITOR_NAME);
        assertThat(all.getFirst().details()).containsEntry("job", id);
        assertThat(all.stream().map(AuditEvent::type)).contains(AuditEventType.DOCUMENT_UPLOADED);
    }

    @Test
    void auditFailureIs500AndStartsNoJob() {
        page("/docs/guide/", "raiz");
        doThrow(new AuditException()).when(auditLog).record(any());

        ResponseEntity<String> response = post(EDITOR_KEY, body(root("/docs/guide/"), "0"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(wire.getAllServeEvents()).isEmpty();
        assertThat(store.listMetadata()).isEmpty();
    }

    private static String newKey() {
        String base62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
        StringBuilder key = new StringBuilder("sage_");
        for (int i = 0; i < 40; i++) {
            key.append(base62.charAt(RANDOM.nextInt(base62.length())));
        }
        return key.toString();
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
