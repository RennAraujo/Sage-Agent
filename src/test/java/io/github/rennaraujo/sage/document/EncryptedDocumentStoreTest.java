package io.github.rennaraujo.sage.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rennaraujo.sage.crypto.CryptoException;
import io.github.rennaraujo.sage.crypto.EnvelopeCipher;
import io.github.rennaraujo.sage.crypto.LocalKeyProvider;
import io.github.rennaraujo.sage.storage.BlobStorage;
import io.github.rennaraujo.sage.storage.LocalBlobStorage;
import io.github.rennaraujo.sage.testsupport.ScratchDirs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class EncryptedDocumentStoreTest {

    private static final String CANARY = "SEGREDO-CANARIO-123";
    private static final int MASTER_KEY_BYTES = 32;
    private static final String BLOB_KEY_PATTERN = "docs/[0-9a-f-]{36}";

    private Path dir;

    private EnvelopeCipher cipher;
    private BlobStorage blobs;
    private List<Object> events;
    private EncryptedDocumentStore store;

    @BeforeEach
    void setUp() {
        dir = ScratchDirs.create("sage-store");
        byte[] master = new byte[MASTER_KEY_BYTES];
        new SecureRandom().nextBytes(master);
        cipher = new EnvelopeCipher(new LocalKeyProvider(master));
        blobs = new LocalBlobStorage(dir);
        events = new CopyOnWriteArrayList<>();
        store = newStore();
    }

    @AfterEach
    void tearDown() {
        ScratchDirs.delete(dir);
    }

    private EncryptedDocumentStore newStore() {
        return new EncryptedDocumentStore(cipher, blobs, new FrontmatterParser(), events::add);
    }

    private static Document publicDoc() {
        return new Document(
                new DocumentMetadata(
                        DocumentId.of("publico/java/25/stream-api.md"),
                        Source.PUBLIC,
                        "java",
                        Optional.of("25"),
                        "Streams",
                        List.of("Stream"),
                        Optional.of(java.net.URI.create("https://example.com/stream")),
                        LocalDate.of(2026, 10, 1),
                        true),
                "# Stream API\n" + CANARY + "\n");
    }

    private static Document internalDoc() {
        return new Document(
                new DocumentMetadata(
                        DocumentId.of("interno/portal-x/faturamento.md"),
                        Source.INTERNAL,
                        "java",
                        Optional.empty(),
                        "Faturamento",
                        List.of(),
                        Optional.empty(),
                        LocalDate.of(2026, 10, 2),
                        false),
                "regras internas\n");
    }

    @Test
    void saveThenFind() {
        Document doc = publicDoc();

        store.save(doc);

        assertThat(store.find(doc.metadata().id())).hasValue(doc);
        assertThat(store.find(DocumentId.of("interno/x/y.md"))).isEmpty();
        assertThat(store.listMetadata()).containsExactly(doc.metadata());
    }

    @Test
    void blobKeysAreOpaque() {
        store.save(publicDoc());
        store.save(internalDoc());

        List<String> keys = blobs.list("docs/");

        assertThat(keys).hasSize(2).allSatisfy(key -> {
            assertThat(key).matches(BLOB_KEY_PATTERN);
            assertThat(key).doesNotContain("java").doesNotContain("portal");
        });
    }

    @Test
    void noPlaintextOnDisk() throws IOException {
        store.save(publicDoc());
        store.save(internalDoc());

        try (Stream<Path> files = Files.walk(dir)) {
            List<Path> all = files.toList();
            for (Path file : all) {
                assertThat(file.toString().substring(dir.toString().length()))
                        .doesNotContain("java")
                        .doesNotContain("portal")
                        .doesNotContain("stream");
                if (Files.isRegularFile(file)) {
                    String content = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
                    assertThat(content)
                            .doesNotContain(CANARY)
                            .doesNotContain("publico")
                            .doesNotContain("portal-x")
                            .doesNotContain("Faturamento");
                }
            }
        }
    }

    @Test
    void saveSameIdReplaces() {
        Document doc = publicDoc();
        Document changed = new Document(doc.metadata(), "novo corpo\n");

        store.save(doc);
        store.save(changed);

        assertThat(blobs.list("docs/")).hasSize(1);
        assertThat(store.find(doc.metadata().id())).hasValue(changed);
        assertThat(store.listMetadata()).hasSize(1);
    }

    @Test
    void deleteRemovesBlob() {
        Document doc = publicDoc();
        store.save(doc);

        store.delete(doc.metadata().id());

        assertThat(blobs.list("docs/")).isEmpty();
        assertThat(store.find(doc.metadata().id())).isEmpty();
        assertThat(store.listMetadata()).isEmpty();
    }

    @Test
    void reloadsCatalogOnNewInstance() {
        store.save(publicDoc());
        store.save(internalDoc());

        EncryptedDocumentStore reloaded = newStore();

        assertThat(reloaded.listMetadata())
                .containsExactlyInAnyOrder(publicDoc().metadata(), internalDoc().metadata());
        assertThat(reloaded.find(publicDoc().metadata().id())).hasValue(publicDoc());
        assertThat(reloaded.loadAll()).containsExactlyInAnyOrder(publicDoc(), internalDoc());
    }

    @Test
    void startupFailsOnUndecryptableBlobWithoutLeakingContent() {
        store.save(publicDoc());
        String key = blobs.list("docs/").getFirst();
        byte[] bytes = blobs.get(key).orElseThrow();
        bytes[bytes.length - 1] ^= 1;
        blobs.put(key, bytes);

        assertThatThrownBy(this::newStore)
                .isInstanceOf(CryptoException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(CANARY));
    }

    @Test
    void publishesSavedAndDeletedEvents() {
        Document doc = publicDoc();

        store.save(doc);
        store.delete(doc.metadata().id());
        store.delete(doc.metadata().id());

        assertThat(events)
                .containsExactly(
                        new DocumentSavedEvent(doc),
                        new DocumentDeletedEvent(doc.metadata().id()));
    }

    @Test
    void concurrentSavesOfSameIdKeepOneBlob() throws Exception {
        Document doc = publicDoc();
        int threads = 8;
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            IntStream.range(0, threads).forEach(i -> futures.add(pool.submit(() -> store.save(doc))));
            for (java.util.concurrent.Future<?> future : futures) {
                future.get();
            }
        }

        assertThat(blobs.list("docs/")).hasSize(1);
        assertThat(store.listMetadata()).hasSize(1);
    }

    @Test
    void lastEventMatchesFinalStateWhenSavesAndDeletesInterleave() throws Exception {
        Document doc = publicDoc();
        DocumentId id = doc.metadata().id();
        int threads = 8;
        int rounds = 200;
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                boolean saver = t % 2 == 0;
                futures.add(pool.submit(() -> {
                    for (int i = 0; i < rounds; i++) {
                        if (saver) {
                            store.save(doc);
                        } else {
                            store.delete(id);
                        }
                    }
                }));
            }
            for (java.util.concurrent.Future<?> future : futures) {
                future.get();
            }
        }

        Object last = events.getLast();
        assertThat(last instanceof DocumentSavedEvent).isEqualTo(store.find(id).isPresent());
        assertThat(blobs.list("docs/")).hasSize(store.listMetadata().size());
    }

    @Test
    void logsNeverContainDocumentIds(CapturedOutput output) {
        Document doc = internalDoc();

        store.save(doc);
        store.delete(doc.metadata().id());
        store.save(doc);
        newStore();

        assertThat(output.getAll())
                .contains("Documento salvo")
                .doesNotContain("portal")
                .doesNotContain("faturamento")
                .doesNotContain(CANARY);
    }

    @Test
    void bodyWithCrlfRoundTripsEqual() {
        Document doc = new Document(internalDoc().metadata(), "a\r\nb\rc\n");

        store.save(doc);

        assertThat(doc.body()).isEqualTo("a\nb\nc\n");
        assertThat(store.find(doc.metadata().id())).hasValue(doc);
    }
}
