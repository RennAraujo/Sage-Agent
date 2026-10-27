package io.github.rennaraujo.sage.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

import io.github.rennaraujo.sage.audit.AuditEvent;
import io.github.rennaraujo.sage.audit.AuditEventType;
import io.github.rennaraujo.sage.audit.AuditException;
import io.github.rennaraujo.sage.audit.AuditLog;
import io.github.rennaraujo.sage.auth.RateLimitedAction;
import io.github.rennaraujo.sage.auth.RateLimiter;
import io.github.rennaraujo.sage.document.Document;
import io.github.rennaraujo.sage.document.DocumentId;
import io.github.rennaraujo.sage.document.DocumentStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

@SpringBootTest(webEnvironment = RANDOM_PORT, properties = "sage.models.dir=target/sem-modelos")
@AutoConfigureTestRestTemplate
@ExtendWith(OutputCaptureExtension.class)
class UploadControllerTest {

    private static final String CANARY = "SEGREDO-CANARIO-123";
    private static final String BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int KEY_BODY_CHARS = 40;
    private static final int MASTER_KEY_BYTES = 32;
    private static final int KB = 1024;
    private static final int FILE_LIMIT_KB = 20;
    private static final int ZIP_LIMIT_KB = 50;
    private static final int TOTAL_LIMIT_KB = 25;
    private static final int UPLOADS_PER_MINUTE = 1000;
    private static final String EDITOR_NAME = "editor-teste";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String READER_KEY = newKey();
    private static final String EDITOR_KEY = newKey();

    private static final String INTERNAL_DOC = """
            ---
            id: interno/portal-x/faturamento.md
            fonte: interno
            linguagem: java
            secao: Faturamento
            ---
            # Faturamento

            """ + CANARY + "\n";

    @TempDir
    static Path storageDir;

    @TempDir
    static Path multipartDir;

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
        registry.add("spring.servlet.multipart.location", multipartDir::toString);
        registry.add("sage.rate-limit.uploads-per-minute", () -> UPLOADS_PER_MINUTE);
        registry.add("sage.upload.max-file-bytes", () -> FILE_LIMIT_KB + "KB");
        registry.add("sage.upload.max-zip-bytes", () -> ZIP_LIMIT_KB + "KB");
        registry.add("sage.upload.max-zip-uncompressed-bytes", () -> TOTAL_LIMIT_KB + "KB");
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    DocumentStore store;

    @MockitoBean
    AuditLog auditLog;

    @MockitoSpyBean
    RateLimiter rateLimiter;

    @BeforeEach
    void cleanStore() {
        store.listMetadata().forEach(m -> store.delete(m.id()));
    }

    @Test
    void uploadsMarkdownWithFrontmatter() {
        ResponseEntity<String> response = upload("faturamento.md", INTERNAL_DOC, Map.of());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).contains("\"ids\":[\"interno/portal-x/faturamento.md\"]");
        Document saved =
                store.find(DocumentId.of("interno/portal-x/faturamento.md")).orElseThrow();
        assertThat(saved.body()).contains(CANARY);
        assertThat(saved.metadata().reviewed()).isFalse();
    }

    @Test
    void convertsPdfToMarkdown() {
        byte[] pdf = Fixtures.pdf("Manual do faturamento PDF-CANARIO-777");

        ResponseEntity<String> response = upload(
                "Manual de Faturamento.pdf", pdf, Map.of("fonte", "interno", "linguagem", "java", "secao", "Manual"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).contains("interno/java/manual-de-faturamento.md");
        Document saved = store.find(DocumentId.of("interno/java/manual-de-faturamento.md"))
                .orElseThrow();
        assertThat(saved.body()).contains("PDF-CANARIO-777");
    }

    @Test
    void convertsDocx() {
        byte[] docx = Fixtures.docx("Guia de uso", "Passo a passo DOCX-CANARIO-888");

        ResponseEntity<String> response =
                upload("guia.docx", docx, Map.of("fonte", "interno", "linguagem", "java", "secao", "Guia"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Document saved = store.find(DocumentId.of("interno/java/guia.md")).orElseThrow();
        assertThat(saved.body()).contains("Guia de uso", "DOCX-CANARIO-888");
    }

    @Test
    void usesFormFieldsWhenNoFrontmatter() {
        Map<String, String> fields = Map.of(
                "fonte", "publico",
                "linguagem", "Java",
                "versao", "25",
                "secao", "Instalação",
                "url_origem", "https://docs.oracle.com/en/java/javase/25/");

        ResponseEntity<String> response = upload("Guia de Instalação.md", "# Instalar\n\nBaixe o JDK.\n", fields);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).contains("publico/java/25/guia-de-instalacao.md");
        Document saved = store.find(DocumentId.of("publico/java/25/guia-de-instalacao.md"))
                .orElseThrow();
        assertThat(saved.metadata().section()).isEqualTo("Instalação");
        assertThat(saved.metadata().language()).isEqualTo("java");
        assertThat(saved.body()).isEqualTo("# Instalar\n\nBaixe o JDK.\n");
    }

    @Test
    void internalDocumentUsesFileNameAsSectionWhenNoneIsGiven() {
        ResponseEntity<String> response =
                upload("Cobrança Mensal.txt", "Texto simples.\n", Map.of("fonte", "interno", "linguagem", "portal-x"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Document saved =
                store.find(DocumentId.of("interno/portal-x/cobranca-mensal.md")).orElseThrow();
        assertThat(saved.metadata().section()).isEqualTo("Cobrança Mensal");
    }

    @Test
    void frontmatterWithoutIdGetsTheDerivedId() {
        String doc = "---\nfonte: interno\nlinguagem: java\nsecao: Notas\n---\nTexto\n";

        ResponseEntity<String> response = upload("notas.md", doc, Map.of("fonte", "interno", "linguagem", "java"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).contains("interno/java/notas.md");
    }

    @Test
    void missingIdSourceIs422WithoutEchoingContent() {
        ResponseEntity<String> response = upload("semcampos.md", "# Sem nada\n" + CANARY + "\n", Map.of());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody()).doesNotContain(CANARY);
        assertThat(store.listMetadata()).isEmpty();
    }

    @Test
    void invalidFormFieldsAre422() {
        assertThat(upload("a.md", "texto", Map.of("fonte", "externo", "linguagem", "java"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(upload("a.md", "texto", Map.of("fonte", "interno", "linguagem", "ja\nva"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(upload("a.md", "texto", Map.of("fonte", "interno", "linguagem", "c#"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void latin1TextStoredAsUtf8() {
        String text = "# Configuração\n\nA configuração do módulo de faturamento é feita pela variável de ambiente, "
                + "e o serviço de cobrança também lê essa informação na inicialização.\n";

        ResponseEntity<String> response = uploadBytes(
                "configuracao.txt",
                text.getBytes(StandardCharsets.ISO_8859_1),
                Map.of("fonte", "interno", "linguagem", "java", "secao", "Config"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Document saved =
                store.find(DocumentId.of("interno/java/configuracao.md")).orElseThrow();
        assertThat(saved.body()).isEqualTo(text);
    }

    @Test
    void rejectsExecutableRenamedAsMd() {
        ResponseEntity<String> response = uploadBytes(
                "leia-me.md",
                DocumentConverterTest.windowsExecutable(),
                Map.of("fonte", "interno", "linguagem", "java", "secao", "x"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(store.listMetadata()).isEmpty();
    }

    @Test
    void rejectsDocumentWithSecret() {
        String key = "AKIA" + "IOSFODNN7EXAMPLE";
        String doc = INTERNAL_DOC.replace("# Faturamento", "# Faturamento\nChave: " + key);
        int line = doc.substring(0, doc.indexOf(key)).split("\n", -1).length;

        ResponseEntity<String> response = upload("faturamento.md", doc, Map.of());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody())
                .contains("{\"tipo\":\"aws-access-key\",\"linha\":" + line + "}")
                .doesNotContain(key)
                .doesNotContain(CANARY);
        assertThat(store.listMetadata()).isEmpty();
    }

    @Test
    void secretLineIsThirdLineInASimpleDocument() {
        String doc = "linha um\nlinha dois\nchave AKIA" + "IOSFODNN7EXAMPLE\n";

        ResponseEntity<String> response =
                upload("a.md", doc, Map.of("fonte", "interno", "linguagem", "java", "secao", "s"));

        assertThat(response.getBody()).contains("{\"tipo\":\"aws-access-key\",\"linha\":3}");
    }

    @Test
    void zipUploadsEveryMarkdownEntryAndIgnoresSystemJunk() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("vault/", new byte[0]);
        entries.put("vault/Um.md", "# Um\n".getBytes(StandardCharsets.UTF_8));
        entries.put("vault/sub/Dois.txt", "dois\n".getBytes(StandardCharsets.UTF_8));
        entries.put("__MACOSX/vault/._Um.md", "lixo".getBytes(StandardCharsets.UTF_8));
        entries.put(
                "vault/com-id.md",
                "---\nid: interno/outro-app/com-id.md\nfonte: interno\nlinguagem: outro\nsecao: S\n---\ncorpo\n"
                        .getBytes(StandardCharsets.UTF_8));

        ResponseEntity<String> response = uploadBytes(
                "vault.zip",
                ZipExtractorTest.zip(entries),
                Map.of("fonte", "interno", "linguagem", "portal-x", "secao", "Vault"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody())
                .contains("interno/portal-x/um.md", "interno/portal-x/dois.md", "interno/outro-app/com-id.md");
        assertThat(store.listMetadata()).hasSize(3);
        verify(rateLimiter, times(1)).require(EDITOR_NAME, RateLimitedAction.UPLOAD);
        verify(auditLog, times(3)).record(any(AuditEvent.class));
    }

    @Test
    void zipWithSecretInOneEntrySavesNothingAndNamesTheEntry() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("ok.md", "# ok\n".getBytes(StandardCharsets.UTF_8));
        entries.put("pasta/ruim.md", ("a\nb\nAKIA" + "IOSFODNN7EXAMPLE\n").getBytes(StandardCharsets.UTF_8));

        ResponseEntity<String> response = uploadBytes(
                "vault.zip",
                ZipExtractorTest.zip(entries),
                Map.of("fonte", "interno", "linguagem", "portal-x", "secao", "Vault"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.getBody())
                .contains("{\"tipo\":\"aws-access-key\",\"linha\":3}", "\"arquivo\":\"ruim.md\"")
                .doesNotContain("pasta")
                .doesNotContain("AKIA");
        assertThat(store.listMetadata()).isEmpty();
    }

    @Test
    void zipWithTraversalEntryRejected() {
        ResponseEntity<String> response = uploadBytes(
                "evil.zip",
                ZipExtractorTest.zip(Map.of("../evil.md", "x".getBytes(StandardCharsets.UTF_8))),
                Map.of("fonte", "interno", "linguagem", "java", "secao", "x"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(store.listMetadata()).isEmpty();
    }

    @Test
    void zipWithUnsupportedEntryIs415AndEmptyZipIs422() {
        Map<String, byte[]> withImage = Map.of("foto.png", new byte[] {1, 2, 3});
        Map<String, byte[]> onlyJunk = Map.of("__MACOSX/x.md", new byte[] {'x'});
        Map<String, String> fields = Map.of("fonte", "interno", "linguagem", "java", "secao", "x");

        assertThat(uploadBytes("a.zip", ZipExtractorTest.zip(withImage), fields).getStatusCode())
                .isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(uploadBytes("b.zip", ZipExtractorTest.zip(onlyJunk), fields).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void zipWithDuplicateDerivedIdsIs422() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("a/guia.md", "um\n".getBytes(StandardCharsets.UTF_8));
        entries.put("b/guia.md", "dois\n".getBytes(StandardCharsets.UTF_8));

        ResponseEntity<String> response = uploadBytes(
                "dup.zip",
                ZipExtractorTest.zip(entries),
                Map.of("fonte", "interno", "linguagem", "java", "secao", "x"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(store.listMetadata()).isEmpty();
    }

    @Test
    void zipBombRejected() {
        byte[] big = "a".repeat((FILE_LIMIT_KB - 1) * KB).getBytes(StandardCharsets.UTF_8);
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("a.md", big);
        entries.put("b.md", big);
        byte[] zip = ZipExtractorTest.zip(entries);

        ResponseEntity<String> response =
                uploadBytes("bomba.zip", zip, Map.of("fonte", "interno", "linguagem", "java", "secao", "x"));

        assertThat(zip.length).isLessThan(KB);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(store.listMetadata()).isEmpty();
    }

    @Test
    void fileOverLimitRejected() {
        String text = "a".repeat((FILE_LIMIT_KB + 1) * KB);

        ResponseEntity<String> response =
                upload("grande.txt", text, Map.of("fonte", "interno", "linguagem", "java", "secao", "x"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void requestOverTheMultipartLimitIs413Problem() {
        byte[] huge = new byte[(ZIP_LIMIT_KB + 20) * KB];
        java.util.Arrays.fill(huge, (byte) 'a');

        ResponseEntity<String> response =
                uploadBytes("enorme.txt", huge, Map.of("fonte", "interno", "linguagem", "java", "secao", "x"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void missingFilePartIs400() {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("fonte", "interno");
        HttpHeaders headers = editorHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<String> response =
                rest.exchange("/api/documentos", HttpMethod.POST, new HttpEntity<>(parts, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void listReturnsMetadataOnly() {
        upload("faturamento.md", INTERNAL_DOC, Map.of());

        ResponseEntity<String> response = get("/api/documentos", EDITOR_KEY);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .doesNotContain(CANARY)
                .contains("\"id\":\"interno/portal-x/faturamento.md\"")
                .contains("\"fonte\":\"interno\"")
                .contains("\"linguagem\":\"java\"")
                .contains("\"secao\":\"Faturamento\"")
                .contains("\"revisado\":false")
                .contains("\"coletado_em\":\"")
                .doesNotContain("versao");
    }

    @Test
    void listShowsVersionForPublicDocuments() {
        upload(
                "stream.md",
                "# Streams\n",
                Map.of(
                        "fonte",
                        "publico",
                        "linguagem",
                        "java",
                        "versao",
                        "25",
                        "secao",
                        "Streams",
                        "url_origem",
                        "https://docs.oracle.com/"));

        assertThat(get("/api/documentos", EDITOR_KEY).getBody()).contains("\"versao\":\"25\"");
    }

    @Test
    void patchMarksReviewed() {
        upload("faturamento.md", INTERNAL_DOC, Map.of());

        ResponseEntity<String> response =
                patch("/api/documentos?id=interno/portal-x/faturamento.md", "{\"revisado\":true}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Document saved =
                store.find(DocumentId.of("interno/portal-x/faturamento.md")).orElseThrow();
        assertThat(saved.metadata().reviewed()).isTrue();
        assertThat(saved.body()).contains(CANARY);
        assertThat(get("/api/documentos", EDITOR_KEY).getBody()).contains("\"revisado\":true");
    }

    @Test
    void patchWithoutRevisadoIs400AndUnknownIdIs404WithoutEchoingTheId() {
        upload("faturamento.md", INTERNAL_DOC, Map.of());

        assertThat(patch("/api/documentos?id=interno/portal-x/faturamento.md", "{}")
                        .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        ResponseEntity<String> missing =
                patch("/api/documentos?id=interno/portal-x/nao-existe.md", "{\"revisado\":true}");

        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(missing.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(missing.getBody()).doesNotContain("nao-existe", "portal-x");
    }

    @Test
    void deleteRemoves() {
        upload("faturamento.md", INTERNAL_DOC, Map.of());

        ResponseEntity<String> response = delete("/api/documentos?id=interno/portal-x/faturamento.md");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(store.listMetadata()).isEmpty();
        ResponseEntity<String> again = delete("/api/documentos?id=interno/portal-x/faturamento.md");
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(again.getBody()).doesNotContain("faturamento");
    }

    @Test
    void invalidIdParameterIs422WithoutEchoingIt() {
        ResponseEntity<String> response = delete("/api/documentos?id=../../etc/passwd");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.getBody()).doesNotContain("passwd");
    }

    @Test
    void readerGets403() {
        assertThat(get("/api/documentos", READER_KEY).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(uploadAs(READER_KEY, "a.md", INTERNAL_DOC.getBytes(StandardCharsets.UTF_8), Map.of())
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(delete("/api/documentos?id=interno/portal-x/faturamento.md", READER_KEY)
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(store.listMetadata()).isEmpty();
    }

    @Test
    void uploadAndDeleteAreAudited() {
        upload("faturamento.md", INTERNAL_DOC, Map.of());
        patch("/api/documentos?id=interno/portal-x/faturamento.md", "{\"revisado\":true}");
        delete("/api/documentos?id=interno/portal-x/faturamento.md");

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditLog, times(3)).record(captor.capture());
        List<AuditEvent> events = captor.getAllValues();
        assertThat(events)
                .extracting(AuditEvent::type)
                .containsExactly(
                        AuditEventType.DOCUMENT_UPLOADED,
                        AuditEventType.DOCUMENT_REVIEWED,
                        AuditEventType.DOCUMENT_DELETED);
        assertThat(events).extracting(AuditEvent::actor).containsOnly(EDITOR_NAME);
        assertThat(events)
                .allSatisfy(event -> assertThat(event.details())
                        .containsEntry("document", "interno/portal-x/faturamento.md")
                        .doesNotContainValue(CANARY));
        assertThat(events.getFirst().details().values()).noneMatch(v -> v.contains(CANARY));
    }

    @Test
    void failedUploadsAreNotAudited() {
        upload("a.md", "texto sem campos\n", Map.of());

        verify(auditLog, times(0)).record(any(AuditEvent.class));
    }

    @Test
    void rateLimitIsConsumedOncePerUpload() {
        upload("faturamento.md", INTERNAL_DOC, Map.of());

        verify(rateLimiter, times(1)).require(EDITOR_NAME, RateLimitedAction.UPLOAD);
    }

    @Test
    void documentTextNeverReachesTheLogs(CapturedOutput output) {
        upload("faturamento.md", INTERNAL_DOC, Map.of());
        upload("manual.pdf", Fixtures.pdf("PDF-CANARIO-LOG"), Map.of("fonte", "interno", "linguagem", "java"));
        upload("ruim.md", "chave AKIA" + "IOSFODNN7EXAMPLE " + CANARY + "\n", Map.of());
        uploadBytes("lixo.md", DocumentConverterTest.elfExecutable(), Map.of());

        assertThat(output.getAll())
                .doesNotContain(CANARY, "PDF-CANARIO-LOG", "AKIA" + "IOSFODNN7EXAMPLE", "interno/portal-x");
    }

    // ---------- rodada de correção 1 ----------

    private static Map<String, String> internalFields() {
        return Map.of("fonte", "interno", "linguagem", "java", "secao", "x");
    }

    @Test
    void multipartPartsAreNotSpooledToDisk() throws Exception {
        String text = "# Grande\n" + "linha de texto comum para passar do limite antigo\n".repeat(300);
        try (TempWatcher watcher = new TempWatcher(multipartDir)) {
            ResponseEntity<String> response = upload("grande.md", text, internalFields());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(watcher.created()).isEmpty();
        }
        try (java.util.stream.Stream<Path> files = Files.list(multipartDir)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void auditFailureOnUploadSavesNothingAndReturnsProblem500() {
        doThrow(new AuditException()).when(auditLog).record(any(AuditEvent.class));

        ResponseEntity<String> response = upload("faturamento.md", INTERNAL_DOC, Map.of());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody()).doesNotContain(CANARY, "faturamento", "Exception");
        assertThat(store.listMetadata()).isEmpty();
    }

    @Test
    void auditFailureOnReviewAndDeleteLeavesTheDocumentUntouched() {
        upload("faturamento.md", INTERNAL_DOC, Map.of());
        doThrow(new AuditException()).when(auditLog).record(any(AuditEvent.class));

        ResponseEntity<String> review =
                patch("/api/documentos?id=interno/portal-x/faturamento.md", "{\"revisado\":true}");
        ResponseEntity<String> delete = delete("/api/documentos?id=interno/portal-x/faturamento.md");

        assertThat(review.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(delete.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(delete.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        Document kept =
                store.find(DocumentId.of("interno/portal-x/faturamento.md")).orElseThrow();
        assertThat(kept.metadata().reviewed()).isFalse();
    }

    @Test
    void existingIdIsConflictUnlessReplaceIsRequested() {
        upload("faturamento.md", INTERNAL_DOC, Map.of());
        patch("/api/documentos?id=interno/portal-x/faturamento.md", "{\"revisado\":true}");
        String other = INTERNAL_DOC.replace(CANARY, "NOVO-TEXTO");

        ResponseEntity<String> conflict = upload("faturamento.md", other, Map.of());

        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(conflict.getBody()).doesNotContain("faturamento", "portal-x");
        Document kept =
                store.find(DocumentId.of("interno/portal-x/faturamento.md")).orElseThrow();
        assertThat(kept.body()).contains(CANARY);
        assertThat(kept.metadata().reviewed()).isTrue();

        ResponseEntity<String> replaced = upload("faturamento.md", other, Map.of("substituir", "true"));

        assertThat(replaced.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replaced.getBody())
                .contains("\"ids\":[\"interno/portal-x/faturamento.md\"]")
                .contains("\"substituidos\":[\"interno/portal-x/faturamento.md\"]");
        assertThat(store.find(DocumentId.of("interno/portal-x/faturamento.md"))
                        .orElseThrow()
                        .body())
                .contains("NOVO-TEXTO");
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditLog, times(3)).record(captor.capture());
        assertThat(captor.getAllValues().getLast().details()).containsEntry("substituido", "true");
        assertThat(captor.getAllValues().getFirst().details()).doesNotContainKey("substituido");
    }

    @Test
    void newUploadReportsNoReplacements() {
        ResponseEntity<String> response = upload("faturamento.md", INTERNAL_DOC, Map.of());

        assertThat(response.getBody()).contains("\"substituidos\":[]");
    }

    @Test
    void zipWithOneConflictAndNoFlagSavesNothing() {
        upload("b.md", "antigo\n", internalFields());
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("a.md", "novo a\n".getBytes(StandardCharsets.UTF_8));
        entries.put("b.md", "novo b\n".getBytes(StandardCharsets.UTF_8));

        ResponseEntity<String> response = uploadBytes("lote.zip", ZipExtractorTest.zip(entries), internalFields());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(store.listMetadata()).hasSize(1);
        assertThat(store.find(DocumentId.of("interno/java/b.md")).orElseThrow().body())
                .contains("antigo");
    }

    @Test
    void errorFileFieldEchoesOnlyTheCappedLastSegment() {
        String longName = "n".repeat(150) + ".md";
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("pasta/sub/" + longName, ("a\nAKIA" + "IOSFODNN7EXAMPLE\n").getBytes(StandardCharsets.UTF_8));

        ResponseEntity<String> response = uploadBytes("lote.zip", ZipExtractorTest.zip(entries), internalFields());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.getBody())
                .doesNotContain("pasta", "sub/")
                .contains("\"arquivo\":\"" + "n".repeat(100) + "\"");
    }

    @Test
    void formFieldsAreScannedForSecrets() {
        Map<String, String> fields =
                Map.of("fonte", "interno", "linguagem", "java", "secao", "chave AKIA" + "IOSFODNN7EXAMPLE");

        ResponseEntity<String> response = upload("a.md", "texto\n", fields);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.getBody()).contains("\"tipo\":\"aws-access-key\"").doesNotContain("AKIA");
        assertThat(store.listMetadata()).isEmpty();
    }

    @Test
    void idIsDerivedFromFrontmatterFieldsWhenThereIsNoIdAndNoFormFields() {
        String internal = "---\nfonte: interno\nlinguagem: Java\nsecao: Notas\n---\nTexto\n";
        String publico = "---\nfonte: publico\nlinguagem: java\nversao: \"25\"\nsecao: S\n"
                + "url_origem: https://exemplo.com/\n---\nTexto\n";

        ResponseEntity<String> first = upload("Notas Gerais.md", internal, Map.of());
        ResponseEntity<String> second = upload("Streams API.md", publico, Map.of());

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(first.getBody()).contains("interno/java/notas-gerais.md");
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getBody()).contains("publico/java/25/streams-api.md");
    }

    @Test
    void formAndFrontmatterDisagreementIs422WithFieldOnlyMessage() {
        String doc = "---\nfonte: interno\nlinguagem: java\nsecao: S\n---\nTexto\n";

        ResponseEntity<String> response =
                upload("a.md", doc, Map.of("fonte", "interno", "linguagem", "kotlin-segredo"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.getBody()).contains("linguagem").doesNotContain("kotlin-segredo");
        assertThat(store.listMetadata()).isEmpty();
    }

    // ---------- helpers ----------

    private ResponseEntity<String> upload(String filename, String content, Map<String, String> fields) {
        return uploadBytes(filename, content.getBytes(StandardCharsets.UTF_8), fields);
    }

    private ResponseEntity<String> upload(String filename, byte[] content, Map<String, String> fields) {
        return uploadBytes(filename, content, fields);
    }

    private ResponseEntity<String> uploadBytes(String filename, byte[] content, Map<String, String> fields) {
        return uploadAs(EDITOR_KEY, filename, content, fields);
    }

    private ResponseEntity<String> uploadAs(String key, String filename, byte[] content, Map<String, String> fields) {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        fields.forEach(parts::add);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(key);
        return rest.exchange("/api/documentos", HttpMethod.POST, new HttpEntity<>(parts, headers), String.class);
    }

    private ResponseEntity<String> get(String path, String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(key);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> patch(String path, String json) {
        HttpHeaders headers = editorHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.PATCH, new HttpEntity<>(json, headers), String.class);
    }

    private ResponseEntity<String> delete(String path) {
        return delete(path, EDITOR_KEY);
    }

    private ResponseEntity<String> delete(String path, String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(key);
        return rest.exchange(path, HttpMethod.DELETE, new HttpEntity<>(headers), String.class);
    }

    private static HttpHeaders editorHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(EDITOR_KEY);
        return headers;
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
