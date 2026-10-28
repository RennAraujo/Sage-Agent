package io.github.rennaraujo.sage.question;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

import io.github.rennaraujo.sage.audit.AuditException;
import io.github.rennaraujo.sage.audit.AuditLog;
import io.github.rennaraujo.sage.auth.RateLimitedAction;
import io.github.rennaraujo.sage.auth.RateLimiter;
import io.github.rennaraujo.sage.document.DocumentId;
import io.github.rennaraujo.sage.document.Source;
import io.github.rennaraujo.sage.question.llm.LanguageModel;
import io.github.rennaraujo.sage.question.llm.ModelEvent;
import io.github.rennaraujo.sage.search.Chunk;
import io.github.rennaraujo.sage.search.IndexReadiness;
import io.github.rennaraujo.sage.search.Retriever;
import io.github.rennaraujo.sage.search.ScoredChunk;
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
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import reactor.core.publisher.Flux;

@SpringBootTest(webEnvironment = RANDOM_PORT, properties = "sage.models.dir=target/sem-modelos")
@AutoConfigureTestRestTemplate
class QuestionControllerTest {

    private static final int MASTER_KEY_BYTES = 32;
    private static final int MAX_CHARS = 2000;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String READER_KEY = "sage_" + HexFormat.of().formatHex(randomBytes(20));
    private static final String CANARY = "PERGUNTA-CANARIO-42";

    private static final Path STORAGE_DIR = ScratchDirs.create("sage-teste-");

    @AfterAll
    static void deleteScratchDirs() {
        ScratchDirs.delete(STORAGE_DIR);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        Path keyFile = Files.createTempFile("sage-test-master", ".key");
        keyFile.toFile().deleteOnExit();
        Files.writeString(keyFile, Base64.getEncoder().encodeToString(randomBytes(MASTER_KEY_BYTES)));
        registry.add("sage.crypto.provider", () -> "local");
        registry.add("sage.crypto.local-key-file", keyFile::toString);
        registry.add("sage.storage.type", () -> "local");
        registry.add("sage.storage.local-dir", STORAGE_DIR::toString);
        registry.add("sage.auth.keys[0].name", () -> "leitor-teste");
        registry.add("sage.auth.keys[0].role", () -> "READER");
        registry.add("sage.auth.keys[0].sha256", () -> sha256Hex(READER_KEY));
        registry.add("sage.models.fast-id", () -> "modelo-rapido-teste");
        registry.add("sage.models.strong-id", () -> "modelo-forte-teste");
    }

    @Autowired
    TestRestTemplate rest;

    @MockitoBean
    Retriever retriever;

    @MockitoBean
    LanguageModel languageModel;

    @MockitoBean
    IndexReadiness readiness;

    @MockitoBean
    AuditLog auditLog;

    @MockitoSpyBean
    RateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        when(readiness.isReady()).thenReturn(true);
        when(languageModel.available()).thenReturn(true);
        when(retriever.rerankerActive()).thenReturn(true);
    }

    @Test
    void streamsEventStream() {
        Chunk chunk = new Chunk(
                "c1",
                DocumentId.of("publico/java/21/streams.md"),
                Source.PUBLIC,
                "java",
                Optional.of("21"),
                "Streams",
                "Use toList().",
                0,
                true);
        when(retriever.retrieve(anyString(), any())).thenReturn(List.of(new ScoredChunk(chunk, 0.9)));
        when(languageModel.stream(any(), any()))
                .thenReturn(Flux.just(
                        new ModelEvent.TextDelta("Use toList() [1]. "),
                        new ModelEvent.TextDelta("Sem fonte."),
                        new ModelEvent.Completed()));

        ResponseEntity<String> response = ask("{\"pergunta\":\"como usar toList no java? " + CANARY + "\"}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .isTrue();
        String body = response.getBody();
        assertThat(body).contains("event:metadata", "event:text", "event:done");
        assertThat(body.indexOf("event:metadata")).isLessThan(body.indexOf("event:text"));
        assertThat(body.indexOf("event:text")).isLessThan(body.indexOf("event:done"));
        assertThat(body)
                .contains("\"texto\":\"Use toList() [1].\"")
                .contains("\"documento\":\"publico/java/21/streams.md\"")
                .contains("\"rota\":\"FAST_MODEL\"")
                .contains("\"frasesDescartadas\":1")
                .doesNotContain("Sem fonte.")
                .doesNotContain(CANARY);
        String consulted = "\"documentacaoConsultada\":[{\"linguagem\":\"java\",\"nome\":\"Java\",\"versao\":\"21\","
                + "\"fonte\":\"publico\",\"versaoNaoInformada\":false}]";
        assertThat(body.substring(body.indexOf("event:done"))).contains(consulted);
        assertThat(body.substring(0, body.indexOf("event:text"))).doesNotContain("documentacaoConsultada");
        verify(rateLimiter).require("leitor-teste", RateLimitedAction.QUESTION);
        verify(auditLog, times(2)).record(any());
    }

    @Test
    void payloadReportsRedactedSecrets() {
        AnswerMetadata meta = new AnswerMetadata(
                "c-1",
                Route.FAST_MODEL,
                Optional.empty(),
                0.9,
                List.of(),
                Optional.empty(),
                false,
                1,
                2,
                java.util.Map.of(),
                List.of());

        assertThat(QuestionController.payload(meta))
                .containsEntry("frasesDescartadas", 1)
                .containsEntry("segredosRemovidos", 2);
    }

    @Test
    void donePayloadMarksUnknownVersionWithNull() {
        AnswerMetadata meta = new AnswerMetadata(
                "c-1",
                Route.FAST_MODEL,
                Optional.empty(),
                0.9,
                List.of(),
                Optional.empty(),
                false,
                0,
                0,
                java.util.Map.of(),
                List.of(new ConsultedSource("java", "Java", Optional.empty(), Source.PUBLIC)));

        @SuppressWarnings("unchecked")
        List<java.util.Map<String, Object>> consulted = (List<java.util.Map<String, Object>>)
                QuestionController.donePayload(meta).get("documentacaoConsultada");

        assertThat(consulted)
                .singleElement()
                .satisfies(source -> assertThat(source)
                        .containsEntry("linguagem", "java")
                        .containsEntry("versao", null)
                        .containsEntry("fonte", "publico")
                        .containsEntry("versaoNaoInformada", true));
        assertThat(QuestionController.payload(meta)).doesNotContainKey("documentacaoConsultada");
    }

    @Test
    void questionOver2000CharsIs400() {
        String tooLong = "a".repeat(MAX_CHARS + 1);

        ResponseEntity<String> response = ask("{\"pergunta\":\"" + tooLong + "\"}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody()).doesNotContain(tooLong);
        verify(retriever, never()).retrieve(anyString(), any());
    }

    @Test
    void blankQuestionIs400() {
        ResponseEntity<String> response = ask("{\"pergunta\":\"   \"}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void indexNotReadyIs503WithRetryAfter() {
        when(readiness.isReady()).thenReturn(false);

        ResponseEntity<String> response = ask("{\"pergunta\":\"como usar toList?\"}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("5");
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        verify(retriever, never()).retrieve(anyString(), any());
    }

    @Test
    void modelNotConfiguredIs503() {
        when(languageModel.available()).thenReturn(false);

        ResponseEntity<String> response = ask("{\"pergunta\":\"como usar toList?\"}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).contains("modelo não configurado");
    }

    @Test
    void modelFailureBecomesErrorEvent() {
        Chunk chunk = new Chunk(
                "c1",
                DocumentId.of("publico/java/21/streams.md"),
                Source.PUBLIC,
                "java",
                Optional.of("21"),
                "Streams",
                "Use toList().",
                0,
                true);
        when(retriever.retrieve(anyString(), any())).thenReturn(List.of(new ScoredChunk(chunk, 0.9)));
        when(languageModel.stream(any(), any())).thenReturn(Flux.error(new IllegalStateException(CANARY)));

        ResponseEntity<String> response = ask("{\"pergunta\":\"como usar toList no java?\"}");

        assertThat(response.getBody()).contains("event:error").doesNotContain(CANARY);
        assertThat(response.getBody()).contains("event:metadata", "\"conversaId\"");
        assertThat(response.getBody().indexOf("event:metadata"))
                .isLessThan(response.getBody().indexOf("event:error"));
    }

    @Test
    void noKeyIs401OnRealEndpoint() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.TEXT_EVENT_STREAM));

        ResponseEntity<String> response = rest.exchange(
                "/api/perguntas",
                HttpMethod.POST,
                new HttpEntity<>("{\"pergunta\":\"como usar toList?\"}", headers),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(retriever, never()).retrieve(anyString(), any());
    }

    @Test
    void auditDownStreamsNoText() {
        Chunk chunk = new Chunk(
                "c1",
                DocumentId.of("publico/java/21/streams.md"),
                Source.PUBLIC,
                "java",
                Optional.of("21"),
                "Streams",
                "Use toList().",
                0,
                true);
        when(retriever.retrieve(anyString(), any())).thenReturn(List.of(new ScoredChunk(chunk, 0.9)));
        doThrow(new AuditException()).when(auditLog).record(any());

        ResponseEntity<String> response = ask("{\"pergunta\":\"como usar toList no java?\"}");

        assertThat(response.getBody())
                .contains("event:error")
                .doesNotContain("event:text")
                .doesNotContain("event:metadata");
        verify(languageModel, never()).stream(any(), any());
    }

    private ResponseEntity<String> ask(String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(READER_KEY);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.TEXT_EVENT_STREAM));
        return rest.exchange("/api/perguntas", HttpMethod.POST, new HttpEntity<>(json, headers), String.class);
    }

    private static byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        RANDOM.nextBytes(bytes);
        return bytes;
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
