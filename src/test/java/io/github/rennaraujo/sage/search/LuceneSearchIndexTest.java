package io.github.rennaraujo.sage.search;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rennaraujo.sage.crypto.EnvelopeCipher;
import io.github.rennaraujo.sage.crypto.LocalKeyProvider;
import io.github.rennaraujo.sage.document.Document;
import io.github.rennaraujo.sage.document.DocumentId;
import io.github.rennaraujo.sage.document.DocumentMetadata;
import io.github.rennaraujo.sage.document.EncryptedDocumentStore;
import io.github.rennaraujo.sage.document.FrontmatterParser;
import io.github.rennaraujo.sage.document.Source;
import io.github.rennaraujo.sage.search.embedding.EmbeddingModel;
import io.github.rennaraujo.sage.storage.LocalBlobStorage;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LuceneSearchIndexTest {

    private static final int LIMIT = 10;
    private static final int MASTER_KEY_BYTES = 32;
    private static final int CONCURRENT_THREADS = 2;
    private static final int UPSERTS_PER_THREAD = 50;
    private static final int HUGE_QUERY_TERMS = 5000;

    private LuceneSearchIndex index;

    @BeforeEach
    void setUp() {
        index = new LuceneSearchIndex(new RetrievalProperties(1500, 20, 5, 0.35, 0.0), new IndexReadiness());
    }

    @AfterEach
    void tearDown() {
        index.close();
    }

    static Document doc(String id, String language, String version, String body) {
        DocumentId documentId = DocumentId.of(id);
        DocumentMetadata meta = new DocumentMetadata(
                documentId,
                documentId.source(),
                language,
                Optional.ofNullable(version),
                "geral",
                List.of(),
                documentId.source() == Source.PUBLIC
                        ? Optional.of(URI.create("https://docs.example.org/"))
                        : Optional.empty(),
                LocalDate.of(2026, 10, 27),
                true);
        return new Document(meta, body);
    }

    private static Document javaDoc(String version, String body) {
        return doc("publico/java/" + version + "/streams.md", "java", version, body);
    }

    private static SearchFilter anything() {
        return new SearchFilter(Set.of(), Optional.empty(), List.of());
    }

    @Test
    void findsExactIdentifier() {
        index.upsert(javaDoc(
                "25",
                "# Streams\nUse Stream.toList() para obter uma lista imutável.\n\n# Outro\nTexto sobre coleções.\n"));

        List<Chunk> found = index.search("Stream.toList", anything(), LIMIT);

        assertThat(found).isNotEmpty();
        assertThat(found.get(0).text()).contains("Stream.toList()");
        assertThat(found.get(0).headingPath()).isEqualTo("Streams");
        assertThat(found.get(0).language()).isEqualTo("java");
        assertThat(found.get(0).version()).contains("25");
        assertThat(found.get(0).source()).isEqualTo(Source.PUBLIC);
        assertThat(found.get(0).reviewed()).isTrue();
    }

    @Test
    void foldsCaseAndAccents() {
        index.upsert(javaDoc("25", "# Coleções\nA coleção imutável não aceita alterações.\n"));

        assertThat(index.search("COLECAO", anything(), LIMIT)).hasSize(1);
    }

    @Test
    void vectorFindsCrossLanguageChunk() {
        String english = "# Sorting\nHow to sort a List in Java with a Comparator.\n";
        String other = "# TLS\nConfigure TLS certificate on the server.\n";
        index.upsert(doc("publico/java/25/sort.md", "java", "25", english));
        index.upsert(doc("publico/java/25/tls.md", "java", "25", other));
        assertThat(index.search("como ordenar lista", anything(), LIMIT)).isEmpty();

        try (LuceneSearchIndex hybrid = hybridIndex(new FakeEmbeddingModel())) {
            hybrid.upsert(doc("publico/java/25/sort.md", "java", "25", english));
            hybrid.upsert(doc("publico/java/25/tls.md", "java", "25", other));

            List<Chunk> found = hybrid.search("como ordenar lista", anything(), LIMIT);

            assertThat(found).isNotEmpty();
            assertThat(found.get(0).text()).contains("sort a List");
        }
    }

    @Test
    void hybridSearchRespectsFilterOnTheVectorBranch() {
        try (LuceneSearchIndex hybrid = hybridIndex(new FakeEmbeddingModel())) {
            hybrid.upsert(doc("publico/java/25/sort.md", "java", "25", "# Sorting\nHow to sort a List.\n"));
            hybrid.upsert(doc("publico/java/21/sort.md", "java", "21", "# Sorting\nHow to sort a List.\n"));
            hybrid.upsert(doc("interno/guia/sort.md", "pt", null, "# Ordenar\nComo ordenar uma lista.\n"));

            SearchFilter v25 = new SearchFilter(Set.of(Source.PUBLIC), Optional.of("java"), List.of("25"));
            SearchFilter internal = new SearchFilter(Set.of(Source.INTERNAL), Optional.empty(), List.of());

            assertThat(hybrid.search("ordenar lista", v25, LIMIT))
                    .singleElement()
                    .satisfies(c -> assertThat(c.version()).contains("25"));
            assertThat(hybrid.search("ordenar lista", internal, LIMIT))
                    .singleElement()
                    .satisfies(c -> assertThat(c.source()).isEqualTo(Source.INTERNAL));
        }
    }

    @Test
    void upsertReplacesVectorsOfThePreviousVersion() {
        try (LuceneSearchIndex hybrid = hybridIndex(new FakeEmbeddingModel())) {
            hybrid.upsert(doc("publico/java/25/x.md", "java", "25", "# Sorting\nHow to sort a List.\n"));
            hybrid.upsert(doc("publico/java/25/x.md", "java", "25", "# TLS\nConfigure TLS certificate.\n"));

            List<Chunk> found = hybrid.search("ordenar lista", anything(), LIMIT);

            assertThat(found).allSatisfy(c -> assertThat(c.text()).doesNotContain("sort a List"));
        }
    }

    @Test
    void embeddingFailureDegradesToBm25() {
        EmbeddingModel broken = new EmbeddingModel() {
            @Override
            public float[] embedQuery(String text) {
                throw new IllegalStateException("sem modelo");
            }

            @Override
            public float[] embedPassage(String text) {
                throw new IllegalStateException("sem modelo");
            }

            @Override
            public int dimensions() {
                return 5;
            }
        };
        try (LuceneSearchIndex hybrid = hybridIndex(broken)) {
            hybrid.upsert(javaDoc("25", "# Streams\nStream.toList() devolve lista.\n"));

            assertThat(hybrid.search("Stream.toList", anything(), LIMIT)).hasSize(1);
        }
    }

    private static LuceneSearchIndex hybridIndex(EmbeddingModel model) {
        return new LuceneSearchIndex(
                new RetrievalProperties(1500, 20, 5, 0.35, 0.0), new IndexReadiness(), Optional.of(model));
    }

    @Test
    void filtersByVersion() {
        index.upsert(javaDoc("25", "# Registros\nrecord patterns no Java.\n"));
        index.upsert(javaDoc("21", "# Registros\nrecord patterns no Java.\n"));

        List<Chunk> found =
                index.search("record patterns", new SearchFilter(Set.of(), Optional.of("java"), List.of("25")), LIMIT);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).version()).contains("25");
    }

    @Test
    void filtersBySourceAndLanguage() {
        index.upsert(javaDoc("25", "# Sessão\nobjeto sessão do usuário\n"));
        index.upsert(doc("interno/pagamentos/fluxo.md", "pt", null, "# Sessão\nobjeto sessão do usuário\n"));

        SearchFilter internal = new SearchFilter(Set.of(Source.INTERNAL), Optional.empty(), List.of());
        SearchFilter javaOnly = new SearchFilter(Set.of(Source.PUBLIC), Optional.of("JAVA"), List.of());

        assertThat(index.search("sessão", internal, LIMIT))
                .singleElement()
                .satisfies(c -> assertThat(c.source()).isEqualTo(Source.INTERNAL));
        assertThat(index.search("sessão", javaOnly, LIMIT))
                .singleElement()
                .satisfies(c -> assertThat(c.language()).isEqualTo("java"));
    }

    @Test
    void languageAndVersionConstrainOnlyPublicChunks() {
        index.upsert(doc("publico/java/25/a.md", "java", "25", "# A\ntermo comum\n"));
        index.upsert(doc("publico/java/21/a.md", "java", "21", "# A\ntermo comum\n"));
        index.upsert(doc("publico/spring/25/a.md", "spring", "25", "# A\ntermo comum\n"));
        index.upsert(doc("interno/pagamentos/fluxo.md", "pt", null, "# A\ntermo comum\n"));

        SearchFilter both =
                new SearchFilter(Set.of(Source.PUBLIC, Source.INTERNAL), Optional.of("java"), List.of("25"));
        List<Chunk> found = index.search("termo comum", both, LIMIT);

        assertThat(found)
                .extracting(
                        c -> c.source() + "/" + c.language() + "/" + c.version().orElse("-"))
                .containsExactlyInAnyOrder("PUBLIC/java/25", "INTERNAL/pt/-");

        SearchFilter publicOnly = new SearchFilter(Set.of(Source.PUBLIC), Optional.of("java"), List.of("25"));
        assertThat(index.search("termo comum", publicOnly, LIMIT)).hasSize(1);

        SearchFilter internalOnly = new SearchFilter(Set.of(Source.INTERNAL), Optional.of("java"), List.of("25"));
        assertThat(index.search("termo comum", internalOnly, LIMIT)).hasSize(1);

        // Sem fontes informadas valem as duas, cada uma com a sua regra.
        SearchFilter noSources = new SearchFilter(Set.of(), Optional.of("java"), List.of("25"));
        assertThat(index.search("termo comum", noSources, LIMIT)).hasSize(2);
    }

    @Test
    void exactFieldMatchesPunctuationOnlyIdentifiers() {
        index.upsert(javaDoc("25", "# Lambdas\nUse a seta -> para o corpo e :: para referência de método.\n"));

        // Nenhum termo sobrevive ao tokenizer padrão: só o campo exact pode casar.
        assertThat(index.search("->", anything(), LIMIT)).hasSize(1);
        assertThat(index.search("::", anything(), LIMIT)).hasSize(1);
        assertThat(index.search("=>", anything(), LIMIT)).isEmpty();
    }

    @Test
    void exactMatchOutranksPlainWordMatch() {
        index.upsert(doc(
                "publico/java/25/a.md",
                "java",
                "25",
                "# Anotação\nUse @Transactional em List<String> nos serviços.\n"));
        index.upsert(doc("publico/java/25/b.md", "java", "25", "# Anotação\nTransactional\n"));

        List<Chunk> found = index.search("@Transactional", anything(), LIMIT);

        assertThat(found).hasSize(2);
        assertThat(found.get(0).text()).contains("@Transactional");
    }

    @Test
    void preReleaseVersionsSortBeforeTheRelease() {
        for (String v : List.of("25", "25-ea", "4.0", "4.0-rc1", "4.0.1", "26")) {
            index.upsert(doc("publico/java/" + v + "/a.md", "java", v, "# A\ntexto\n"));
        }

        assertThat(index.versionsOf("java")).containsExactly("4.0-rc1", "4.0", "4.0.1", "25-ea", "25", "26");
    }

    @Test
    void upsertSameDocReplacesChunks() {
        Document d = javaDoc("25", "# A\nprimeiro\n\n# B\nsegundo\n\n# C\nterceiro\n");
        DocumentId id = d.metadata().id();

        index.upsert(d);
        int first = index.chunksOf(id).size();
        index.upsert(d);

        assertThat(first).isEqualTo(3);
        assertThat(index.chunksOf(id)).hasSize(first);
        assertThat(index.search("segundo", anything(), LIMIT)).hasSize(1);
    }

    @Test
    void upsertShrinkingDocDropsOldChunks() {
        DocumentId id = DocumentId.of("publico/java/25/streams.md");
        index.upsert(javaDoc("25", "# A\nprimeiro\n\n# B\nsegundo\n"));
        index.upsert(javaDoc("25", "# A\nsó um agora\n"));

        assertThat(index.chunksOf(id)).hasSize(1);
        assertThat(index.search("segundo", anything(), LIMIT)).isEmpty();
    }

    @Test
    void concurrentUpsertsLeaveOneCopy() throws Exception {
        Document d = javaDoc("25", "# A\nprimeiro\n\n# B\nsegundo\n\n# C\nterceiro\n");
        int expected = 3;
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_THREADS);
        try {
            List<Future<?>> futures = IntStream.range(0, CONCURRENT_THREADS)
                    .<Future<?>>mapToObj(t -> pool.submit(
                            () -> IntStream.range(0, UPSERTS_PER_THREAD).forEach(i -> index.upsert(d))))
                    .toList();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(index.chunksOf(d.metadata().id())).hasSize(expected);
        assertThat(index.search("segundo", anything(), LIMIT)).hasSize(1);
    }

    @Test
    void removeDeletesChunks() {
        Document d = javaDoc("25", "# A\nprimeiro\n");
        index.upsert(d);

        index.remove(d.metadata().id());
        index.remove(d.metadata().id());

        assertThat(index.chunksOf(d.metadata().id())).isEmpty();
        assertThat(index.search("primeiro", anything(), LIMIT)).isEmpty();
        assertThat(index.versionsOf("java")).isEmpty();
    }

    @Test
    void versionsOfReturnsSorted() {
        index.upsert(doc("publico/java/25/a.md", "java", "25", "# A\ntexto\n"));
        index.upsert(doc("publico/java/21/a.md", "java", "21", "# A\ntexto\n"));
        index.upsert(doc("publico/spring/4.10/a.md", "spring", "4.10", "# A\ntexto\n"));
        index.upsert(doc("publico/spring/4.9/a.md", "spring", "4.9", "# A\ntexto\n"));
        index.upsert(doc("publico/spring/3.5/a.md", "spring", "3.5", "# A\ntexto\n"));
        index.upsert(doc("publico/spring/4.1/b.md", "spring", "4.1", "# A\ntexto\n"));
        index.upsert(doc("interno/app/fluxo.md", "java", null, "# A\ntexto\n"));

        assertThat(index.versionsOf("java")).containsExactly("21", "25");
        assertThat(index.versionsOf("Spring")).containsExactly("3.5", "4.1", "4.9", "4.10");
        assertThat(index.versionsOf("rust")).isEmpty();
    }

    @Test
    void chunkIdsAreDeterministicAndDoNotExposeDocumentId() {
        Document d = javaDoc("25", "# A\nprimeiro\n\n# B\nsegundo\n");
        index.upsert(d);
        List<String> first =
                index.chunksOf(d.metadata().id()).stream().map(Chunk::id).toList();
        index.upsert(d);
        List<String> second =
                index.chunksOf(d.metadata().id()).stream().map(Chunk::id).toList();

        assertThat(first).isEqualTo(second).hasSize(2).doesNotHaveDuplicates();
        assertThat(first).allSatisfy(id -> assertThat(id).doesNotContain("java").doesNotContain("streams"));
        assertThat(index.chunksOf(d.metadata().id())).extracting(Chunk::ordinal).containsExactly(0, 1);
    }

    @Test
    void userInputIsNeverQuerySyntax() {
        index.upsert(javaDoc("25", "# A\ntexto simples\n"));

        for (String hostile : List.of(
                "(", "\"", "AND OR NOT", "title:[a TO", "foo~~2^^", "*:*", "\\", "a:b:c", "+-!(){}[]^\"~*?:\\/")) {
            assertThat(index.search(hostile, anything(), LIMIT)).isNotNull();
        }
        assertThat(index.search("*:*", anything(), LIMIT)).isEmpty();
    }

    @Test
    void blankQueryOrNonPositiveLimitGivesNothing() {
        index.upsert(javaDoc("25", "# A\ntexto\n"));

        assertThat(index.search("", anything(), LIMIT)).isEmpty();
        assertThat(index.search("   ", anything(), LIMIT)).isEmpty();
        assertThat(index.search("!!!", anything(), LIMIT)).isEmpty();
        assertThat(index.search("texto", anything(), 0)).isEmpty();
    }

    @Test
    void veryLongQueryDoesNotBlowUp() {
        index.upsert(javaDoc("25", "# A\ntexto\n"));
        String huge = IntStream.range(0, HUGE_QUERY_TERMS)
                .mapToObj(i -> "termo" + i)
                .collect(Collectors.joining(" ", "texto ", ""));

        assertThat(index.search(huge, anything(), LIMIT)).hasSize(1);
    }

    @Test
    void writesNothingToDisk(@TempDir Path data) throws IOException {
        byte[] master = new byte[MASTER_KEY_BYTES];
        new SecureRandom().nextBytes(master);
        EncryptedDocumentStore store = new EncryptedDocumentStore(
                new EnvelopeCipher(new LocalKeyProvider(master)),
                new LocalBlobStorage(data),
                new FrontmatterParser(),
                e -> {});
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        Path cwd = Path.of("").toAbsolutePath();
        Set<String> tmpBefore = luceneFiles(tmp);
        Set<String> cwdBefore = luceneFiles(cwd);

        store.save(javaDoc("25", "# Streams\nStream.toList() devolve lista imutável. SEGREDO-CANARIO-123\n"));
        try (Stream<Document> all = store.loadAll()) {
            all.forEach(index::upsert);
        }
        assertThat(index.search("canario", anything(), LIMIT)).isNotEmpty();
        index.close();

        try (Stream<Path> files = Files.walk(data)) {
            List<Path> regular = files.filter(Files::isRegularFile).toList();
            assertThat(regular).isNotEmpty();
            assertThat(regular)
                    .allSatisfy(p ->
                            assertThat(data.relativize(p).getName(0).toString()).isEqualTo("docs"));
        }
        assertThat(luceneFiles(tmp)).isEqualTo(tmpBefore);
        assertThat(luceneFiles(cwd)).isEqualTo(cwdBefore);
    }

    private static Set<String> luceneFiles(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".cfs")
                            || n.endsWith(".si")
                            || n.startsWith("segments")
                            || n.startsWith("lucene")
                            || n.equals("write.lock"))
                    .collect(Collectors.toSet());
        }
    }
}
