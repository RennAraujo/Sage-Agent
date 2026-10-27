package io.github.rennaraujo.sage.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.rennaraujo.sage.document.Document;
import io.github.rennaraujo.sage.document.DocumentDeletedEvent;
import io.github.rennaraujo.sage.document.DocumentId;
import io.github.rennaraujo.sage.document.DocumentMetadata;
import io.github.rennaraujo.sage.document.DocumentSavedEvent;
import io.github.rennaraujo.sage.document.DocumentStore;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

class IndexBootstrapperTest {

    private static final int LIMIT = 10;
    private static final long GATE_SECONDS = 10;
    private static final Duration WAIT = Duration.ofSeconds(GATE_SECONDS);

    private IndexReadiness readiness;
    private LuceneSearchIndex index;

    @BeforeEach
    void setUp() {
        readiness = new IndexReadiness();
        index = new LuceneSearchIndex(new RetrievalProperties(1500, 30), readiness);
    }

    @AfterEach
    void tearDown() {
        index.close();
    }

    private static SearchFilter anything() {
        return new SearchFilter(Set.of(), Optional.empty(), List.of());
    }

    private static Document doc(String slug, String body) {
        return LuceneSearchIndexTest.doc("publico/java/25/" + slug + ".md", "java", "25", body);
    }

    /** Armazém falso: loadAll entrega os documentos só depois que o teste libera, para exercitar a corrida. */
    private static class GatedStore implements DocumentStore {
        private final List<Document> docs;
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch started = new CountDownLatch(1);

        GatedStore(List<Document> docs) {
            this.docs = docs;
        }

        @Override
        public void save(Document doc) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Document> find(DocumentId id) {
            return docs.stream().filter(d -> d.metadata().id().equals(id)).findFirst();
        }

        @Override
        public List<DocumentMetadata> listMetadata() {
            return docs.stream().map(Document::metadata).toList();
        }

        @Override
        public void delete(DocumentId id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Stream<Document> loadAll() {
            started.countDown();
            try {
                if (!release.await(GATE_SECONDS, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("teste não liberou o armazém");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return docs.stream();
        }
    }

    @Test
    void notReadyUntilLoaded_thenReady() throws Exception {
        GatedStore store = new GatedStore(List.of(doc("a", "# A\nconteúdo do acervo\n")));
        IndexBootstrapper bootstrapper = new IndexBootstrapper(store, index, readiness);

        assertThat(readiness.isReady()).isFalse();
        assertThat(readiness.health().getStatus()).isEqualTo(Status.DOWN);

        bootstrapper.onApplicationReady();
        assertThat(store.started.await(GATE_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(readiness.isReady()).isFalse();
        assertThat(index.isReady()).isFalse();
        assertThat(readiness.health().getStatus()).isEqualTo(Status.DOWN);

        store.release.countDown();
        await().atMost(WAIT).until(readiness::isReady);

        assertThat(index.isReady()).isTrue();
        assertThat(readiness.health().getStatus()).isEqualTo(Status.UP);
        assertThat(index.search("acervo", anything(), LIMIT)).hasSize(1);
    }

    @Test
    void failedLoadKeepsIndexDown() {
        DocumentStore broken = new GatedStore(List.of()) {
            @Override
            public Stream<Document> loadAll() {
                throw new IllegalStateException("falha simulada");
            }
        };

        new IndexBootstrapper(broken, index, readiness).onApplicationReady();

        await().atMost(WAIT)
                .until(() -> "failed".equals(readiness.health().getDetails().get("state")));
        assertThat(readiness.isReady()).isFalse();
        assertThat(readiness.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(readiness.health().getDetails()).containsEntry("state", "failed");
    }

    @Test
    void reindexesOnSavedAndDeletedEvents() {
        GatedStore store = new GatedStore(List.of());
        store.release.countDown();
        IndexBootstrapper bootstrapper = new IndexBootstrapper(store, index, readiness);
        bootstrapper.onApplicationReady();
        await().atMost(WAIT).until(readiness::isReady);
        Document d = doc("novo", "# Novo\npalavra rara zyzzyva\n");

        bootstrapper.onSaved(new DocumentSavedEvent(d));
        assertThat(index.search("zyzzyva", anything(), LIMIT)).hasSize(1);

        bootstrapper.onSaved(new DocumentSavedEvent(doc("novo", "# Novo\noutra palavra quokka\n")));
        assertThat(index.search("zyzzyva", anything(), LIMIT)).isEmpty();
        assertThat(index.search("quokka", anything(), LIMIT)).hasSize(1);

        bootstrapper.onDeleted(new DocumentDeletedEvent(d.metadata().id()));
        assertThat(index.search("quokka", anything(), LIMIT)).isEmpty();
        assertThat(index.chunksOf(d.metadata().id())).isEmpty();
    }

    @Test
    void deleteDuringBootstrapWinsOverLoadedSnapshot() throws Exception {
        Document stale = doc("apagado", "# X\nversão antiga fantasma\n");
        Document kept = doc("mantido", "# Y\nversão mantida\n");
        GatedStore store = new GatedStore(List.of(stale, kept));
        IndexBootstrapper bootstrapper = new IndexBootstrapper(store, index, readiness);

        bootstrapper.onApplicationReady();
        assertThat(store.started.await(GATE_SECONDS, TimeUnit.SECONDS)).isTrue();
        // O armazém apagou o documento depois do snapshot do loadAll: o evento chega antes do bootstrap alcançá-lo.
        bootstrapper.onDeleted(new DocumentDeletedEvent(stale.metadata().id()));
        store.release.countDown();
        await().atMost(WAIT).until(readiness::isReady);

        assertThat(index.chunksOf(stale.metadata().id())).isEmpty();
        assertThat(index.search("fantasma", anything(), LIMIT)).isEmpty();
        assertThat(index.search("mantida", anything(), LIMIT)).hasSize(1);
    }

    @Test
    void saveDuringBootstrapWinsOverOlderSnapshotWithoutDuplicates() throws Exception {
        Document old = doc("mudou", "# Z\nconteúdo velho\n");
        Document newer = doc("mudou", "# Z\nconteúdo novo\n\n# W\nsegunda seção\n");
        GatedStore store = new GatedStore(List.of(old));
        IndexBootstrapper bootstrapper = new IndexBootstrapper(store, index, readiness);

        bootstrapper.onApplicationReady();
        assertThat(store.started.await(GATE_SECONDS, TimeUnit.SECONDS)).isTrue();
        bootstrapper.onSaved(new DocumentSavedEvent(newer));
        store.release.countDown();
        await().atMost(WAIT).until(readiness::isReady);

        assertThat(index.chunksOf(old.metadata().id())).hasSize(2);
        assertThat(index.search("velho", anything(), LIMIT)).isEmpty();
        assertThat(index.search("novo", anything(), LIMIT)).hasSize(1);
    }

    @Test
    void eventsAfterReadyAreAppliedDirectly() {
        GatedStore store = new GatedStore(List.of());
        store.release.countDown();
        IndexBootstrapper bootstrapper = new IndexBootstrapper(store, index, readiness);
        bootstrapper.onApplicationReady();
        await().atMost(WAIT).until(readiness::isReady);

        bootstrapper.onSaved(new DocumentSavedEvent(doc("p", "# P\ntexto pós bootstrap\n")));

        assertThat(index.search("pós", anything(), LIMIT)).hasSize(1);
    }

    @Test
    void oneBadDocumentDoesNotStopTheLoad() {
        Document bad = doc("ruim", "# Ruim\ntexto ruim\n");
        Document good = doc("bom", "# Bom\ntexto bom\n");
        GatedStore store = new GatedStore(List.of(bad, good));
        store.release.countDown();
        SearchIndex flaky = new SearchIndex() {
            @Override
            public void upsert(Document d) {
                if (d.metadata().id().equals(bad.metadata().id())) {
                    throw new IllegalStateException("falha simulada");
                }
                index.upsert(d);
            }

            @Override
            public void remove(DocumentId id) {
                index.remove(id);
            }

            @Override
            public List<Chunk> search(String query, SearchFilter filter, int limit) {
                return index.search(query, filter, limit);
            }

            @Override
            public List<Chunk> chunksOf(DocumentId id) {
                return index.chunksOf(id);
            }

            @Override
            public java.util.SortedSet<String> versionsOf(String language) {
                return index.versionsOf(language);
            }

            @Override
            public boolean isReady() {
                return index.isReady();
            }
        };

        new IndexBootstrapper(store, flaky, readiness).onApplicationReady();

        await().atMost(WAIT).until(readiness::isReady);
        assertThat(index.search("bom", anything(), LIMIT)).hasSize(1);
        assertThat(index.chunksOf(bad.metadata().id())).isEmpty();
    }

    @Test
    void errorDuringLoadMarksFailedInsteadOfLoadingForever() {
        DocumentStore exploding = new GatedStore(List.of()) {
            @Override
            public Stream<Document> loadAll() {
                throw new AssertionError("erro simulado");
            }
        };

        new IndexBootstrapper(exploding, index, readiness).onApplicationReady();

        await().atMost(WAIT)
                .until(() -> "failed".equals(readiness.health().getDetails().get("state")));
        assertThat(readiness.isReady()).isFalse();
    }
}
