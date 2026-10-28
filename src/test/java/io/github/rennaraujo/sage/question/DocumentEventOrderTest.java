package io.github.rennaraujo.sage.question;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.github.benmanes.caffeine.cache.Ticker;
import io.github.rennaraujo.sage.crypto.EnvelopeCipher;
import io.github.rennaraujo.sage.crypto.LocalKeyProvider;
import io.github.rennaraujo.sage.document.Document;
import io.github.rennaraujo.sage.document.DocumentDeletedEvent;
import io.github.rennaraujo.sage.document.DocumentId;
import io.github.rennaraujo.sage.document.DocumentMetadata;
import io.github.rennaraujo.sage.document.DocumentSavedEvent;
import io.github.rennaraujo.sage.document.DocumentStore;
import io.github.rennaraujo.sage.document.DocumentsChangedEvent;
import io.github.rennaraujo.sage.document.EncryptedDocumentStore;
import io.github.rennaraujo.sage.document.FrontmatterParser;
import io.github.rennaraujo.sage.document.Source;
import io.github.rennaraujo.sage.search.IndexBootstrapper;
import io.github.rennaraujo.sage.search.IndexReadiness;
import io.github.rennaraujo.sage.search.SearchIndex;
import io.github.rennaraujo.sage.storage.LocalBlobStorage;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * Com um contexto Spring de verdade: ao salvar ou apagar um documento, o índice e o catálogo de aliases atualizam antes
 * de o cache de respostas esvaziar, e o cache esvazia mesmo quando o índice falha. O cache é registrado primeiro de
 * propósito, para a ordem de registro não esconder o problema.
 */
class DocumentEventOrderTest {

    @Test
    void cacheListenersRunAfterIndexAndAliasCatalogOnSaveAndDelete() {
        List<String> calls = new CopyOnWriteArrayList<>();
        AnswerCache cache = new AnswerCache(new CacheProperties(Duration.ofHours(6), 10), Ticker.systemTicker());
        SearchIndex index = mock(SearchIndex.class);
        IndexReadiness readiness = mock(IndexReadiness.class);
        when(readiness.isReady()).thenReturn(true);
        doAnswer(call -> record(calls, "index", cache)).when(index).upsert(any(Document.class));
        doAnswer(call -> record(calls, "index", cache)).when(index).remove(any(DocumentId.class));
        AliasCatalog catalog = spy(new AliasCatalog(new FakeCatalogs.Store()));
        doAnswer(call -> {
                    record(calls, "aliases", cache);
                    return call.callRealMethod();
                })
                .when(catalog)
                .onSaved(any());
        doAnswer(call -> {
                    record(calls, "aliases", cache);
                    return call.callRealMethod();
                })
                .when(catalog)
                .onDeleted(any());

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean("answerCache", AnswerCache.class, () -> cache);
            context.registerBean("aliasCatalog", AliasCatalog.class, () -> catalog);
            context.registerBean(
                    "indexBootstrapper",
                    IndexBootstrapper.class,
                    () -> new IndexBootstrapper(mock(DocumentStore.class), index, readiness));
            context.refresh();

            // Como o armazém publica: o evento da mudança e, depois dele, o de "acervo mudou".
            context.publishEvent(new DocumentSavedEvent(mock(Document.class)));
            context.publishEvent(new DocumentsChangedEvent());
            long afterSave = cache.epoch();
            context.publishEvent(new DocumentDeletedEvent(DocumentId.of("publico/java/21/streams.md")));
            context.publishEvent(new DocumentsChangedEvent());

            assertThat(calls)
                    .containsExactlyInAnyOrder("index:0", "aliases:0", "index:" + afterSave, "aliases:" + afterSave);
            assertThat(afterSave).isEqualTo(1);
            assertThat(cache.epoch()).isEqualTo(2);
        }
    }

    @Test
    void cacheIsClearedEvenWhenTheIndexListenerThrowsOnDelete() {
        AnswerCache cache = new AnswerCache(new CacheProperties(Duration.ofHours(6), 10), Ticker.systemTicker());
        SearchIndex index = mock(SearchIndex.class);
        IndexReadiness readiness = mock(IndexReadiness.class);
        when(readiness.isReady()).thenReturn(true);
        doThrow(new IllegalStateException("índice quebrado")).when(index).remove(any(DocumentId.class));
        java.nio.file.Path dir = io.github.rennaraujo.sage.testsupport.ScratchDirs.create("sage-evento-");
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean("answerCache", AnswerCache.class, () -> cache);
            context.registerBean(
                    "indexBootstrapper",
                    IndexBootstrapper.class,
                    () -> new IndexBootstrapper(mock(DocumentStore.class), index, readiness));
            context.refresh();
            byte[] master = new byte[32];
            new java.security.SecureRandom().nextBytes(master);
            EncryptedDocumentStore store = new EncryptedDocumentStore(
                    new EnvelopeCipher(new LocalKeyProvider(master)),
                    new LocalBlobStorage(dir),
                    new FrontmatterParser(),
                    context);
            Document doc = new Document(
                    new DocumentMetadata(
                            DocumentId.of("publico/java/21/streams.md"),
                            Source.PUBLIC,
                            "java",
                            Optional.of("21"),
                            "Streams",
                            List.of(),
                            Optional.empty(),
                            LocalDate.of(2026, 10, 1),
                            true),
                    "# Streams\n");
            store.save(doc);
            CacheKey key = new CacheKey(
                    "streams", Set.of(Source.PUBLIC), Optional.of("java"), List.of("21"), QuestionType.LOOKUP);
            cache.put(key, new CachedAnswer(List.of("Use toList() [1]."), metadata()));
            long before = cache.epoch();

            assertThatThrownBy(() -> store.delete(doc.metadata().id()))
                    .isInstanceOf(io.github.rennaraujo.sage.document.DocumentChangeNotPropagatedException.class)
                    .hasCauseInstanceOf(IllegalStateException.class);

            assertThat(cache.get(key)).isEmpty();
            assertThat(cache.epoch()).isGreaterThan(before);
        } finally {
            io.github.rennaraujo.sage.testsupport.ScratchDirs.delete(dir);
        }
    }

    private static AnswerMetadata metadata() {
        return new AnswerMetadata(
                "c",
                Route.FAST_MODEL,
                Optional.empty(),
                0.9,
                List.of(),
                Optional.of("21"),
                false,
                0,
                0,
                java.util.Map.of(),
                List.of());
    }

    /** Anota quem rodou e a época do cache nesse instante: se o cache já tivesse rodado, a época teria avançado. */
    private static Object record(List<String> calls, String who, AnswerCache cache) {
        calls.add(who + ":" + cache.epoch());
        return null;
    }
}
