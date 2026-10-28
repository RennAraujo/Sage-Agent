package io.github.rennaraujo.sage.gap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

import io.github.rennaraujo.sage.testsupport.ScratchDirs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = RANDOM_PORT, properties = "sage.models.dir=target/sem-modelos")
@AutoConfigureTestRestTemplate
class GapControllerTest {

    private static final String BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int KEY_BODY_CHARS = 40;
    private static final int MASTER_KEY_BYTES = 32;
    private static final int MAX_LIMIT = 500;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String READER_KEY = newKey();
    private static final String EDITOR_KEY = newKey();

    private static final Path STORAGE_DIR = ScratchDirs.create("sage-teste-");

    @AfterAll
    static void deleteScratchDirs() {
        ScratchDirs.delete(STORAGE_DIR);
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
        registry.add("sage.storage.local-dir", STORAGE_DIR::toString);
        registry.add("sage.auth.keys[0].name", () -> "leitor-teste");
        registry.add("sage.auth.keys[0].role", () -> "READER");
        registry.add("sage.auth.keys[0].sha256", () -> sha256Hex(READER_KEY));
        registry.add("sage.auth.keys[1].name", () -> "editor-teste");
        registry.add("sage.auth.keys[1].role", () -> "EDITOR");
        registry.add("sage.auth.keys[1].sha256", () -> sha256Hex(EDITOR_KEY));
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    GapRegistry gaps;

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void readerGets403() {
        assertThat(get("/api/lacunas", READER_KEY).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void anonymousGets401() {
        assertThat(rest.getForEntity("/api/lacunas", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void editorSeesGroupedGapsSortedByTimes() throws Exception {
        gaps.record("Como funciona a cobrança recorrente?", "editor-teste");
        gaps.record("Qual o prazo do estorno no ctrl-gap?", "editor-teste");
        gaps.record("qual o prazo do estorno no CTRL-GAP", "leitor-teste");

        ResponseEntity<String> response = get("/api/lacunas", EDITOR_KEY);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = mapper.readTree(response.getBody());
        assertThat(body.isArray()).isTrue();
        assertThat(body.get(0).get("pergunta").asString()).isEqualToIgnoringCase("qual o prazo do estorno no ctrl-gap");
        assertThat(body.get(0).get("vezes").asInt()).isEqualTo(2);
        assertThat(body.get(0).get("ultimaVez").asString()).matches("\\d{4}-\\d{2}-\\d{2}T.*Z");
        assertThat(body.get(0).propertyNames()).containsExactlyInAnyOrder("pergunta", "ultimaVez", "vezes");
    }

    @Test
    void limitIsAppliedAndClamped() throws Exception {
        for (int i = 0; i < 3; i++) {
            gaps.record("pergunta distinta numero " + i + " do limite", "editor-teste");
        }

        JsonNode one = mapper.readTree(get("/api/lacunas?limite=1", EDITOR_KEY).getBody());
        assertThat(one).hasSize(1);
        ResponseEntity<String> huge = get("/api/lacunas?limite=" + (MAX_LIMIT * 10), EDITOR_KEY);
        assertThat(huge.getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<String> text = get("/api/lacunas?limite=abc", EDITOR_KEY);
        assertThat(text.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(text.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        JsonNode problem = mapper.readTree(text.getBody());
        assertThat(problem.get("status").asInt()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(text.getBody()).doesNotContain("abc");
        // Fora da faixa não é erro: 0 vale 1 e 100000 vale 500.
        assertThat(mapper.readTree(get("/api/lacunas?limite=0", EDITOR_KEY).getBody()))
                .hasSize(1);
        assertThat(mapper.readTree(huge.getBody()).size()).isLessThanOrEqualTo(MAX_LIMIT);
    }

    private ResponseEntity<String> get(String path, String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(key);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private static String newKey() {
        StringBuilder key = new StringBuilder("sage_");
        for (int i = 0; i < KEY_BODY_CHARS; i++) {
            key.append(BASE62.charAt(RANDOM.nextInt(BASE62.length())));
        }
        return key.toString();
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
