package io.github.rennaraujo.sage.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

import io.github.rennaraujo.sage.question.llm.LanguageModel;
import io.github.rennaraujo.sage.question.llm.ModelEvent;
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
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import reactor.core.publisher.Flux;

/**
 * Aplicação completa com o armazenamento local num diretório temporário: sobe um documento com um canário,
 * faz perguntas (modelo de mentira, que cita o canário na resposta), uma delas sem resposta no acervo
 * (grava lacuna), e varre cada byte do diretório de dados e tudo o que foi para o log atrás do canário do
 * documento, do canário da pergunta, do nome da aplicação interna e do id do documento. Nada disso pode
 * aparecer em claro.
 */
@SpringBootTest(
        webEnvironment = RANDOM_PORT,
        properties = {"sage.models.dir=target/sem-modelos", "logging.level.io.github.rennaraujo.sage=TRACE"})
@AutoConfigureTestRestTemplate
@ExtendWith(OutputCaptureExtension.class)
class PlaintextLeakTest {

    private static final String DOC_CANARY = "CANARIO-DOC-9c41e7";
    private static final String QUESTION_CANARY = "SENTINELA-PERGUNTA-5b2d08";
    private static final String APP = "orquestrador-vega";
    private static final String DOC_ID = "interno/" + APP + "/cobranca.md";
    private static final int MASTER_KEY_BYTES = 32;
    private static final int KEY_BODY_CHARS = 40;
    private static final String BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final long HEALTH_WAIT_MILLIS = 30_000;
    private static final long HEALTH_POLL_MILLIS = 200;
    private static final int MIN_FILES = 3;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String READER_KEY = newKey();
    private static final String EDITOR_KEY = newKey();
    private static final Path DATA_DIR = ScratchDirs.create("sage-dados-");
    private static final Path MULTIPART_DIR = ScratchDirs.create("sage-multipart-");
    private static final String DOCUMENT = """
            ---
            id: %s
            fonte: interno
            linguagem: %s
            secao: Cobrança
            ---
            # Cobrança mensal

            A cobrança mensal fecha no quinto dia útil. O marcador interno do documento é %s.
            """.formatted(DOC_ID, APP, DOC_CANARY);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        Path keyFile = Files.createTempFile("sage-test-master", ".key");
        keyFile.toFile().deleteOnExit();
        byte[] master = new byte[MASTER_KEY_BYTES];
        RANDOM.nextBytes(master);
        Files.writeString(keyFile, Base64.getEncoder().encodeToString(master));
        registry.add("sage.crypto.provider", () -> "local");
        registry.add("sage.crypto.local-key-file", keyFile::toString);
        registry.add("sage.storage.type", () -> "local");
        registry.add("sage.storage.local-dir", DATA_DIR::toString);
        registry.add("spring.servlet.multipart.location", MULTIPART_DIR::toString);
        registry.add("sage.auth.keys[0].name", () -> "leitor-teste");
        registry.add("sage.auth.keys[0].role", () -> "READER");
        registry.add("sage.auth.keys[0].sha256", () -> sha256Hex(READER_KEY));
        registry.add("sage.auth.keys[1].name", () -> "editor-teste");
        registry.add("sage.auth.keys[1].role", () -> "EDITOR");
        registry.add("sage.auth.keys[1].sha256", () -> sha256Hex(EDITOR_KEY));
        registry.add("sage.models.fast-id", () -> "modelo-rapido-teste");
        registry.add("sage.models.strong-id", () -> "modelo-forte-teste");
    }

    @Autowired
    TestRestTemplate rest;

    @MockitoBean
    LanguageModel languageModel;

    @AfterAll
    static void cleanUp() {
        ScratchDirs.delete(DATA_DIR);
        ScratchDirs.delete(MULTIPART_DIR);
    }

    @BeforeEach
    void stubModel() {
        when(languageModel.available()).thenReturn(true);
        when(languageModel.stream(any(), any()))
                .thenReturn(Flux.just(
                        new ModelEvent.TextDelta(
                                "A cobrança fecha no quinto dia útil e o marcador é " + DOC_CANARY + " [1]."),
                        new ModelEvent.Completed()));
    }

    @Test
    void noPlaintextInDataDirOrLogs(CapturedOutput output) throws Exception {
        awaitHealthy();
        assertThat(upload().getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> answered = ask("Quando fecha a cobrança do " + APP + "? " + QUESTION_CANARY);
        assertThat(answered.getBody()).contains("event:done").contains(DOC_CANARY);
        ResponseEntity<String> unanswered = ask("zzqxk yyqwv " + APP + " " + QUESTION_CANARY);
        assertThat(unanswered.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(unanswered.getBody()).doesNotContain(DOC_CANARY);

        ResponseEntity<String> gaps = getAsEditor("/api/lacunas");
        assertThat(gaps.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(gaps.getBody()).contains(QUESTION_CANARY).contains("vezes");

        List<Path> files;
        try (Stream<Path> walk = Files.walk(DATA_DIR)) {
            files = walk.filter(Files::isRegularFile).toList();
        }
        assertThat(files).as("blobs, auditoria e lacunas gravados").hasSizeGreaterThanOrEqualTo(MIN_FILES);
        for (Path file : files) {
            String name = DATA_DIR.relativize(file).toString();
            byte[] content = Files.readAllBytes(file);
            for (String secret : List.of(DOC_CANARY, QUESTION_CANARY, APP, DOC_ID, "Cobrança mensal")) {
                assertThat(name).as("nome do arquivo").doesNotContain(secret);
                assertThat(indexOf(content, secret.getBytes(StandardCharsets.UTF_8)))
                        .as("%s em claro em %s", secret, name)
                        .isNegative();
            }
        }
        try (Stream<Path> walk = Files.walk(MULTIPART_DIR)) {
            assertThat(walk.filter(Files::isRegularFile).toList())
                    .as("nada do upload fica no diretório do multipart")
                    .isEmpty();
        }
        String logs = output.getAll();
        for (String secret : List.of(DOC_CANARY, QUESTION_CANARY, APP, DOC_ID, "quinto dia útil")) {
            assertThat(logs).as("log").doesNotContain(secret);
        }
    }

    private ResponseEntity<String> upload() {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("file", new ByteArrayResource(DOCUMENT.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "cobranca.md";
            }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(EDITOR_KEY);
        return rest.exchange("/api/documentos", HttpMethod.POST, new HttpEntity<>(parts, headers), String.class);
    }

    private ResponseEntity<String> getAsEditor(String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(EDITOR_KEY);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> ask(String question) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(READER_KEY);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.TEXT_EVENT_STREAM));
        String json = "{\"pergunta\":\"" + question.replace("\"", "\\\"") + "\"}";
        return rest.exchange("/api/perguntas", HttpMethod.POST, new HttpEntity<>(json, headers), String.class);
    }

    private void awaitHealthy() throws InterruptedException {
        long deadline = System.currentTimeMillis() + HEALTH_WAIT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (rest.getForEntity("/actuator/health", String.class)
                    .getStatusCode()
                    .is2xxSuccessful()) {
                return;
            }
            Thread.sleep(HEALTH_POLL_MILLIS);
        }
        throw new IllegalStateException("O índice não ficou pronto a tempo");
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
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
