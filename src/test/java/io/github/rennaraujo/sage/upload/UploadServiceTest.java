package io.github.rennaraujo.sage.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rennaraujo.sage.audit.AuditEvent;
import io.github.rennaraujo.sage.audit.AuditException;
import io.github.rennaraujo.sage.audit.AuditLog;
import io.github.rennaraujo.sage.auth.RateLimitProperties;
import io.github.rennaraujo.sage.auth.RateLimiter;
import io.github.rennaraujo.sage.document.Document;
import io.github.rennaraujo.sage.document.DocumentId;
import io.github.rennaraujo.sage.document.DocumentMetadata;
import io.github.rennaraujo.sage.document.DocumentStore;
import io.github.rennaraujo.sage.document.FrontmatterParser;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.util.unit.DataSize;

/** Gravação do upload: corrida entre dois envios do mesmo id e a auditoria em duas fases. */
class UploadServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-27T12:00:00Z"), ZoneOffset.UTC);
    private static final String ID = "interno/portal-x/faturamento.md";
    private static final String DOC = """
            ---
            id: interno/portal-x/faturamento.md
            fonte: interno
            linguagem: java
            secao: Faturamento
            ---
            # Faturamento

            texto
            """;

    private final List<AuditEvent> audit = new CopyOnWriteArrayList<>();

    @Test
    @Timeout(10)
    void concurrentUploadsOfTheSameIdSaveOnceAndTheOtherIsAConflict() throws Exception {
        // As duas threads passam juntas pela checagem de "já existe" e chegam juntas à gravação.
        RacingStore store = new RacingStore(new CyclicBarrier(2));
        UploadService service = service(store, audit::add);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Object> first = pool.submit(() -> outcome(service));
            Future<Object> second = pool.submit(() -> outcome(service));
            List<Object> results = List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));

            assertThat(results)
                    .filteredOn(r -> r instanceof UploadService.UploadResult)
                    .hasSize(1);
            assertThat(results)
                    .filteredOn(r -> r instanceof DocumentConflictException)
                    .hasSize(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(store.saves).isEqualTo(0);
        assertThat(audit)
                .extracting(e -> e.details().get("resultado"))
                .containsExactlyInAnyOrder("tentativa", "tentativa", "salvo", "existente");
    }

    @Test
    void uploadIsAuditedAsAttemptBeforeAndSavedAfter() {
        UploadService service = service(new RacingStore(null), audit::add);

        service.upload("editor", request(false));

        assertThat(audit).extracting(e -> e.details().get("resultado")).containsExactly("tentativa", "salvo");
        assertThat(audit).allSatisfy(e -> assertThat(e.details()).containsEntry("document", ID));
    }

    @Test
    void replaceStillUsesAPlainSave() {
        RacingStore store = new RacingStore(null);
        UploadService service = service(store, audit::add);
        service.upload("editor", request(false));

        service.upload("editor", request(true));

        assertThat(store.saves).isEqualTo(1);
        assertThat(audit.getLast().details()).containsEntry("resultado", "salvo");
        assertThat(audit.get(audit.size() - 2).details())
                .containsEntry("resultado", "tentativa")
                .containsEntry("substituido", "true");
    }

    @Test
    void failedWriteIsAuditedAsFailedAndAnAuditFailureThereIsSuppressed() {
        RacingStore store = new RacingStore(null);
        store.failWrites = true;
        AuditLog failingOnOutcome = event -> {
            if (!"tentativa".equals(event.details().get("resultado"))) {
                throw new AuditException();
            }
            audit.add(event);
        };

        assertThatThrownBy(() -> service(store, failingOnOutcome).upload("editor", request(false)))
                .isInstanceOf(IllegalStateException.class)
                .satisfies(e -> assertThat(e.getSuppressed()).hasOnlyElementsOfType(AuditException.class));

        RacingStore other = new RacingStore(null);
        other.failWrites = true;
        audit.clear();
        assertThatThrownBy(() -> service(other, audit::add).upload("editor", request(false)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(audit).extracting(e -> e.details().get("resultado")).containsExactly("tentativa", "falhou");
    }

    @Test
    void storedDocumentWhoseIndexListenerThrowsIsAuditedAsSavedWithAWarning() {
        java.nio.file.Path dir = io.github.rennaraujo.sage.testsupport.ScratchDirs.create("sage-upload-");
        try {
            byte[] master = new byte[32];
            new java.security.SecureRandom().nextBytes(master);
            DocumentStore real = new io.github.rennaraujo.sage.document.EncryptedDocumentStore(
                    new io.github.rennaraujo.sage.crypto.EnvelopeCipher(
                            new io.github.rennaraujo.sage.crypto.LocalKeyProvider(master)),
                    new io.github.rennaraujo.sage.storage.LocalBlobStorage(dir),
                    new FrontmatterParser(),
                    event -> {
                        if (event instanceof io.github.rennaraujo.sage.document.DocumentSavedEvent) {
                            throw new IllegalStateException("índice quebrado");
                        }
                    });

            assertThatThrownBy(() -> service(real, audit::add).upload("editor", request(false)))
                    .isInstanceOf(io.github.rennaraujo.sage.document.DocumentChangeNotPropagatedException.class);

            assertThat(real.find(DocumentId.of(ID))).isPresent();
            assertThat(audit).extracting(e -> e.details().get("resultado")).containsExactly("tentativa", "salvo");
            assertThat(audit.getLast().details()).containsEntry("aviso", "indice");
        } finally {
            io.github.rennaraujo.sage.testsupport.ScratchDirs.delete(dir);
        }
    }

    private Object outcome(UploadService service) {
        try {
            return service.upload("editor", request(false));
        } catch (RuntimeException e) {
            return e;
        }
    }

    private static UploadRequest request(boolean replace) {
        return new UploadRequest(
                "faturamento.md",
                DOC.getBytes(StandardCharsets.UTF_8),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                replace);
    }

    private static UploadService service(DocumentStore store, AuditLog auditLog) {
        UploadProperties props = new UploadProperties(
                DataSize.ofKilobytes(64), DataSize.ofKilobytes(128), 10, DataSize.ofKilobytes(256));
        return new UploadService(
                store,
                new FrontmatterParser(CLOCK),
                new DocumentConverter(props),
                new ZipExtractor(props),
                new SecretScanner(),
                auditLog,
                new RateLimiter(new RateLimitProperties(20, 1000)),
                props,
                CLOCK);
    }

    /** Acervo em memória; com barreira, quem chama {@code find} espera o outro chegar lá também. */
    private static final class RacingStore implements DocumentStore {
        private final Map<DocumentId, Document> docs = new ConcurrentHashMap<>();
        private final CyclicBarrier barrier;
        volatile int saves;
        volatile boolean failWrites;

        RacingStore(CyclicBarrier barrier) {
            this.barrier = barrier;
        }

        @Override
        public void save(Document doc) {
            if (failWrites) {
                throw new IllegalStateException("falha simulada");
            }
            saves++;
            docs.put(doc.metadata().id(), doc);
        }

        @Override
        public boolean saveIfAbsent(Document doc) {
            if (failWrites) {
                throw new IllegalStateException("falha simulada");
            }
            return docs.putIfAbsent(doc.metadata().id(), doc) == null;
        }

        @Override
        public Optional<Document> find(DocumentId id) {
            Optional<Document> found = Optional.ofNullable(docs.get(id));
            if (barrier != null) {
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
            return found;
        }

        @Override
        public List<DocumentMetadata> listMetadata() {
            return docs.values().stream().map(Document::metadata).toList();
        }

        @Override
        public void delete(DocumentId id) {
            docs.remove(id);
        }

        @Override
        public Stream<Document> loadAll() {
            return docs.values().stream();
        }
    }
}
