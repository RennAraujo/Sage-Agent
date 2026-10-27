package io.github.rennaraujo.sage.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = RANDOM_PORT, properties = "sage.models.dir=target/sem-modelos")
@AutoConfigureTestRestTemplate
@Import(TestOnlyController.class)
@ExtendWith(OutputCaptureExtension.class)
class SecurityConfigTest {

    private static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
            + "connect-src 'self'; frame-ancestors 'none'";
    private static final String BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int KEY_BODY_CHARS = 40;
    private static final int MASTER_KEY_BYTES = 32;
    private static final int QUESTIONS_PER_MINUTE = 20;
    private static final int MAX_RETRY_AFTER_SECONDS = 60;
    private static final int HUGE_TOKEN_CHARS = 1_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final String READER_KEY = newKey();
    private static final String EDITOR_KEY = newKey();
    private static final String LIMITED_KEY = newKey();

    @TempDir
    static Path storageDir;

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
        registry.add("sage.auth.keys[1].name", () -> "editor-teste");
        registry.add("sage.auth.keys[1].role", () -> "EDITOR");
        registry.add("sage.auth.keys[1].sha256", () -> sha256Hex(EDITOR_KEY));
        registry.add("sage.auth.keys[2].name", () -> "limitado-teste");
        registry.add("sage.auth.keys[2].role", () -> "READER");
        registry.add("sage.auth.keys[2].sha256", () -> sha256Hex(LIMITED_KEY));
    }

    @Autowired
    TestRestTemplate rest;

    @LocalServerPort
    int port;

    @Test
    void missingKeyReturns401ProblemDetail() {
        ResponseEntity<String> response = post("/api/perguntas", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer");
        assertThat(response.getBody())
                .contains("\"status\":401", "\"title\"", "\"type\"")
                .doesNotContain("Exception");
    }

    @Test
    void invalidKeyReturns401() {
        assertThat(post("/api/perguntas", "Bearer " + newKey()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void wrongSchemeAndEmptyTokenReturn401() {
        assertThat(post("/api/perguntas", "Basic " + READER_KEY).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(post("/api/perguntas", "Bearer ").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(post("/api/perguntas", "Bearer " + "x".repeat(HUGE_TOKEN_CHARS))
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void readerOnEditorEndpointReturns403() {
        ResponseEntity<String> response = get("/api/documentos/teste", "Bearer " + READER_KEY);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody()).contains("\"status\":403").doesNotContain("Exception");
    }

    @Test
    void validKeyReturns200() {
        ResponseEntity<String> reader = post("/api/perguntas", "Bearer " + READER_KEY);
        ResponseEntity<String> editorAsking = post("/api/perguntas", "Bearer " + EDITOR_KEY);
        ResponseEntity<String> editorEndpoint = get("/api/documentos/teste", "Bearer " + EDITOR_KEY);

        assertThat(reader.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reader.getBody()).contains("leitor-teste");
        assertThat(editorAsking.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(editorEndpoint.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/api/documentos",
                "/api/documentos/x",
                "/api/lacunas",
                "/api/lacunas/x",
                "/api/importar",
                "/api/importar/x"
            })
    void editorOnlyPathsRejectReaderAndLetEditorPastSecurity(String path) {
        assertThat(get(path, "Bearer " + READER_KEY).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // Sem controller real ainda: 404 depois da autorização conta como "passou pela segurança".
        assertThat(get(path, "Bearer " + EDITOR_KEY).getStatusCode())
                .isNotIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
        assertThat(get(path, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void publicPathsNeedNoKeyAndOtherPathsAreDenied() {
        assertThat(get("/actuator/health", null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/chat/index.html", null).getStatusCode())
                .isNotIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
        assertThat(get("/qualquer-coisa", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get("/actuator/env", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get("/qualquer-coisa", "Bearer " + EDITOR_KEY).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void securityHeadersPresent() {
        HttpHeaders headers = get("/actuator/health", null).getHeaders();

        assertThat(headers.getFirst("Content-Security-Policy")).isEqualTo(CSP);
        assertThat(headers.getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(headers.getFirst("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(headers.getFirst("X-Frame-Options")).isEqualTo("DENY");
    }

    @Test
    void statelessNoCookiesAndPostNeedsNoCsrfToken() {
        ResponseEntity<String> response = post("/api/perguntas", "Bearer " + EDITOR_KEY);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().get(HttpHeaders.SET_COOKIE)).isNull();
    }

    /**
     * Usa o HttpClient do JDK direto: o cliente Apache do TestRestTemplate obedece ao Retry-After e repete a chamada
     * depois de esperar, o que esconderia o 429.
     */
    @Test
    void twentyFirstQuestionInAMinuteReturns429WithRetryAfter() throws Exception {
        for (int i = 0; i < QUESTIONS_PER_MINUTE; i++) {
            assertThat(rawPost(LIMITED_KEY).statusCode()).isEqualTo(HttpStatus.OK.value());
        }

        HttpResponse<String> blocked = rawPost(LIMITED_KEY);

        assertThat(blocked.statusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(blocked.headers().firstValue(HttpHeaders.CONTENT_TYPE)).contains("application/problem+json");
        long retryAfter = Long.parseLong(
                blocked.headers().firstValue(HttpHeaders.RETRY_AFTER).orElseThrow());
        assertThat(retryAfter).isBetween(1L, (long) MAX_RETRY_AFTER_SECONDS);
        assertThat(blocked.body()).contains("\"status\":429").doesNotContain("Exception");
        // O limite é por chave: outra chave segue livre.
        assertThat(rawPost(READER_KEY).statusCode()).isEqualTo(HttpStatus.OK.value());
    }

    @Test
    void unexpectedErrorsReturn500WithoutLeakingDetails(CapturedOutput output) {
        ResponseEntity<String> response = get("/api/falha", "Bearer " + READER_KEY);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody()).doesNotContain("conteudo-secreto", "Exception", "at io.github");
        assertThat(output.getAll()).doesNotContain("conteudo-secreto");
    }

    @Test
    void keysAreNeverLoggedAndNoDefaultPasswordIsGenerated(CapturedOutput output) {
        post("/api/perguntas", "Bearer " + READER_KEY);
        post("/api/perguntas", "Bearer " + newKey());

        assertThat(output.getAll()).doesNotContain(READER_KEY).doesNotContain("Using generated security password");
    }

    private HttpResponse<String> rawPost(String key) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/perguntas"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + key)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .header(TestOnlyController.HEADER, "1")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private ResponseEntity<String> get(String path, String authorization) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers(authorization)), String.class);
    }

    private ResponseEntity<String> post(String path, String authorization) {
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>("{}", headers(authorization)), String.class);
    }

    private static HttpHeaders headers(String authorization) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(TestOnlyController.HEADER, "1");
        if (authorization != null) {
            headers.set(HttpHeaders.AUTHORIZATION, authorization);
        }
        return headers;
    }

    static String newKey() {
        StringBuilder key = new StringBuilder("sage_");
        for (int i = 0; i < KEY_BODY_CHARS; i++) {
            key.append(BASE62.charAt(RANDOM.nextInt(BASE62.length())));
        }
        return key.toString();
    }

    static String sha256Hex(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
