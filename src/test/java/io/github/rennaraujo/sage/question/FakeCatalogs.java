package io.github.rennaraujo.sage.question;

import io.github.rennaraujo.sage.document.Document;
import io.github.rennaraujo.sage.document.DocumentId;
import io.github.rennaraujo.sage.document.DocumentMetadata;
import io.github.rennaraujo.sage.document.DocumentStore;
import io.github.rennaraujo.sage.document.Source;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/** Armazém em memória só com metadados, para alimentar o catálogo de aliases nos testes. */
final class FakeCatalogs {

    private FakeCatalogs() {}

    static DocumentMetadata internal(String app, List<String> aliases) {
        return new DocumentMetadata(
                DocumentId.of("interno/" + app + "/faturamento.md"),
                Source.INTERNAL,
                app,
                Optional.empty(),
                "faturamento",
                aliases,
                Optional.empty(),
                LocalDate.of(2026, 10, 1),
                true);
    }

    static DocumentMetadata publicDoc(String language, String version) {
        return new DocumentMetadata(
                DocumentId.of("publico/" + language + "/" + version + "/guia.md"),
                Source.PUBLIC,
                language,
                Optional.of(version),
                "guia",
                List.of(),
                Optional.empty(),
                LocalDate.of(2026, 10, 1),
                true);
    }

    static class Store implements DocumentStore {
        final List<DocumentMetadata> items = new ArrayList<>();
        final AtomicInteger reads = new AtomicInteger();
        volatile RuntimeException failure;

        Store(DocumentMetadata... initial) {
            items.addAll(List.of(initial));
        }

        @Override
        public void save(Document doc) {
            items.add(doc.metadata());
        }

        @Override
        public Optional<Document> find(DocumentId id) {
            return Optional.empty();
        }

        @Override
        public List<DocumentMetadata> listMetadata() {
            reads.incrementAndGet();
            if (failure != null) {
                throw failure;
            }
            return List.copyOf(items);
        }

        @Override
        public void delete(DocumentId id) {
            items.removeIf(m -> m.id().equals(id));
        }

        @Override
        public Stream<Document> loadAll() {
            return Stream.empty();
        }
    }
}
