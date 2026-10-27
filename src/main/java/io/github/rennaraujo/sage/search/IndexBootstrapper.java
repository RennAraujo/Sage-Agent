package io.github.rennaraujo.sage.search;

import io.github.rennaraujo.sage.document.Document;
import io.github.rennaraujo.sage.document.DocumentDeletedEvent;
import io.github.rennaraujo.sage.document.DocumentSavedEvent;
import io.github.rennaraujo.sage.document.DocumentStore;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

/**
 * Monta o índice na subida (numa virtual thread, depois de {@link ApplicationReadyEvent}) e o mantém em dia a cada
 * upload ou exclusão.
 *
 * <p>Durante a carga inicial os eventos chegam em paralelo com a leitura do acervo, e a leitura pode estar velha: um
 * documento apagado depois do snapshot ainda aparece no stream, e um sobrescrito aparece na versão antiga. Para o
 * evento sempre ganhar, cada id tocado por evento fica anotado em {@code touched}, e tanto o evento quanto a carga
 * agem sobre o id dentro de {@code touched.compute}, que serializa as duas operações por id. A carga pula ids
 * tocados. Terminada a carga, o mapa é descartado e os eventos vão direto ao índice.
 */
public final class IndexBootstrapper {

    private static final Logger LOG = LoggerFactory.getLogger(IndexBootstrapper.class);

    private final DocumentStore store;
    private final SearchIndex index;
    private final IndexReadiness readiness;
    private final Map<String, Boolean> touched = new ConcurrentHashMap<>();

    public IndexBootstrapper(DocumentStore store, SearchIndex index, IndexReadiness readiness) {
        this.store = store;
        this.index = index;
        this.readiness = readiness;
    }

    @EventListener
    public void onApplicationReady(ApplicationReadyEvent event) {
        onApplicationReady();
    }

    void onApplicationReady() {
        Thread.ofVirtual().name("index-bootstrap").start(this::load);
    }

    @EventListener
    public void onSaved(DocumentSavedEvent event) {
        Document doc = event.document();
        if (readiness.isReady()) {
            index.upsert(doc);
            return;
        }
        touched.compute(doc.metadata().id().value(), (id, previous) -> {
            index.upsert(doc);
            return Boolean.TRUE;
        });
    }

    @EventListener
    public void onDeleted(DocumentDeletedEvent event) {
        if (readiness.isReady()) {
            index.remove(event.id());
            return;
        }
        touched.compute(event.id().value(), (id, previous) -> {
            index.remove(event.id());
            return Boolean.TRUE;
        });
    }

    private void load() {
        long start = System.nanoTime();
        AtomicInteger indexed = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        try (Stream<Document> all = store.loadAll()) {
            all.forEach(doc -> loadOne(doc, indexed, failed));
            // Os upserts da carga não atualizam o leitor um a um; um refresh só, antes de anunciar pronto.
            index.refresh();
        } catch (Throwable e) {
            // Sem a mensagem da causa: ela pode carregar trecho de conteúdo. O tipo basta para diagnosticar.
            LOG.error("Falha ao montar o índice: {}", e.getClass().getName());
            readiness.markFailed();
            touched.clear();
            return;
        }
        readiness.markReady();
        touched.clear();
        LOG.info(
                "Índice pronto: {} documentos ({} com falha) em {} ms",
                indexed.get(),
                failed.get(),
                (System.nanoTime() - start) / 1_000_000);
    }

    /** Um documento ruim não derruba a carga: conta, registra só a referência e a classe da exceção, e segue. */
    private void loadOne(Document doc, AtomicInteger indexed, AtomicInteger failed) {
        try {
            touched.compute(doc.metadata().id().value(), (id, previous) -> {
                if (previous == null) {
                    index.upsertDeferred(doc);
                    indexed.incrementAndGet();
                }
                return previous;
            });
        } catch (RuntimeException e) {
            failed.incrementAndGet();
            LOG.error(
                    "Falha ao indexar {}: {}",
                    doc.metadata().id().logRef(),
                    e.getClass().getName());
        }
    }
}
