package io.github.rennaraujo.sage.question;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.github.benmanes.caffeine.cache.Ticker;
import io.github.rennaraujo.sage.document.Document;
import io.github.rennaraujo.sage.document.DocumentDeletedEvent;
import io.github.rennaraujo.sage.document.DocumentId;
import io.github.rennaraujo.sage.document.DocumentSavedEvent;
import io.github.rennaraujo.sage.document.DocumentStore;
import io.github.rennaraujo.sage.search.IndexBootstrapper;
import io.github.rennaraujo.sage.search.IndexReadiness;
import io.github.rennaraujo.sage.search.SearchIndex;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * Com um contexto Spring de verdade: ao salvar ou apagar um documento, o índice e o catálogo de aliases atualizam antes
 * de o cache de respostas esvaziar. O cache é registrado primeiro de propósito, para o empate de ordem não esconder o
 * problema.
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

            context.publishEvent(new DocumentSavedEvent(mock(Document.class)));
            long afterSave = cache.epoch();
            context.publishEvent(new DocumentDeletedEvent(DocumentId.of("publico/java/21/streams.md")));

            assertThat(calls)
                    .containsExactlyInAnyOrder("index:0", "aliases:0", "index:" + afterSave, "aliases:" + afterSave);
            assertThat(afterSave).isEqualTo(1);
            assertThat(cache.epoch()).isEqualTo(2);
        }
    }

    /** Anota quem rodou e a época do cache nesse instante: se o cache já tivesse rodado, a época teria avançado. */
    private static Object record(List<String> calls, String who, AnswerCache cache) {
        calls.add(who + ":" + cache.epoch());
        return null;
    }
}
