package io.github.rennaraujo.sage.eval;

import io.github.rennaraujo.sage.document.Document;
import io.github.rennaraujo.sage.document.DocumentDefaults;
import io.github.rennaraujo.sage.document.DocumentId;
import io.github.rennaraujo.sage.document.DocumentMetadata;
import io.github.rennaraujo.sage.document.DocumentStore;
import io.github.rennaraujo.sage.document.FrontmatterParser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/** Os documentos de {@code eval/acervo}, lidos do classpath, e um {@link DocumentStore} em memória com eles. */
public final class EvalAcervo {

    private static final String PATTERN = "classpath*:eval/acervo/**/*.md";

    private EvalAcervo() {}

    public static List<Document> load() {
        FrontmatterParser parser = new FrontmatterParser();
        try {
            List<Document> documents = new ArrayList<>();
            for (Resource resource : new PathMatchingResourcePatternResolver().getResources(PATTERN)) {
                String raw = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                documents.add(parser.parse(raw, DocumentDefaults.empty()));
            }
            documents.sort(Comparator.comparing(d -> d.metadata().id().value()));
            return List.copyOf(documents);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * O mesmo acervo com cada seção engordada até perto do teto de 1500 caracteres por trecho: depois do
     * primeiro parágrafo entra um parágrafo de enchimento sem relação com nenhuma pergunta. Serve à
     * calibração do corte de tokens do reranker, porque os trechos reais passam de 1000 caracteres e os do
     * acervo de avaliação não.
     */
    public static List<Document> loadWithLongChunks() {
        return load().stream().map(EvalAcervo::padded).toList();
    }

    private static final int TARGET_SECTION_CHARS = 1400;
    private static final String FILLER = "Operational notes for this area are maintained by the platform team and are "
            + "reviewed every quarter together with the release calendar, the support rotation and the list of "
            + "supported environments. Nothing in this paragraph changes the behavior described around it. ";

    private static final String NL = String.valueOf((char) 10);
    private static final String PARAGRAPH_BREAK = NL + NL;

    private static Document padded(Document doc) {
        StringBuilder out = new StringBuilder();
        List<String> section = new ArrayList<>();
        for (String line : doc.body().split(NL, -1)) {
            if (line.startsWith("#")) {
                flush(section, out);
                section.clear();
                out.append(line).append(NL);
            } else {
                section.add(line);
            }
        }
        flush(section, out);
        return new Document(doc.metadata(), out.toString().stripTrailing() + NL);
    }

    private static void flush(List<String> section, StringBuilder out) {
        String text = String.join(NL, section).strip();
        if (text.isEmpty()) {
            return;
        }
        int firstBreak = text.indexOf(PARAGRAPH_BREAK);
        int missing = TARGET_SECTION_CHARS - text.length();
        if (missing <= 0 || firstBreak < 0) {
            out.append(NL).append(text).append(PARAGRAPH_BREAK);
            return;
        }
        String filler = FILLER.repeat(missing / FILLER.length() + 1)
                .substring(0, missing)
                .strip();
        out.append(NL)
                .append(text, 0, firstBreak)
                .append(PARAGRAPH_BREAK)
                .append(filler)
                .append(text.substring(firstBreak))
                .append(PARAGRAPH_BREAK);
    }

    /** Armazém em memória; o catálogo de aliases só lê os metadados dele. */
    public static final class Store implements DocumentStore {

        private final Map<String, Document> documents = new ConcurrentHashMap<>();

        public Store(List<Document> initial) {
            initial.forEach(this::save);
        }

        @Override
        public void save(Document doc) {
            documents.put(doc.metadata().id().value(), doc);
        }

        @Override
        public boolean saveIfAbsent(Document doc) {
            return documents.putIfAbsent(doc.metadata().id().value(), doc) == null;
        }

        @Override
        public Optional<Document> find(DocumentId id) {
            return Optional.ofNullable(documents.get(id.value()));
        }

        @Override
        public List<DocumentMetadata> listMetadata() {
            return documents.values().stream().map(Document::metadata).toList();
        }

        @Override
        public void delete(DocumentId id) {
            documents.remove(id.value());
        }

        @Override
        public Stream<Document> loadAll() {
            return documents.values().stream();
        }
    }
}
