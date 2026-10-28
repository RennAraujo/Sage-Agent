package io.github.rennaraujo.sage.search;

import io.github.rennaraujo.sage.document.Document;
import io.github.rennaraujo.sage.document.DocumentId;
import io.github.rennaraujo.sage.document.DocumentMetadata;
import io.github.rennaraujo.sage.document.Source;
import io.github.rennaraujo.sage.search.embedding.EmbeddingModel;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.LowerCaseFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.core.WhitespaceTokenizer;
import org.apache.lucene.analysis.miscellaneous.ASCIIFoldingFilter;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.TermInSetQuery;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.util.BytesRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Índice BM25 do Lucene inteiramente em memória ({@link ByteBuffersDirectory}): nada indexado toca o disco, nem em
 * arquivo de índice nem em temporário. Cada trecho vira um documento Lucene com os campos de filtro como
 * {@link StringField} e o texto em dois {@link TextField}: {@code text} (tokenizer padrão, minúsculas, sem acento) e
 * {@code exact} (separado por espaço, só minúsculas) para identificadores como {@code Stream.toList()}.
 *
 * <p>Com um {@link EmbeddingModel}, cada trecho também ganha um vetor ({@code KnnFloatVectorField}, cosseno) e a busca
 * funde BM25 e KNN por RRF ({@link HybridRanker}). Sem modelo, ou se o modelo falhar, a busca é só BM25.
 *
 * <p>A consulta nunca passa pelo parser do Lucene: o texto do usuário é analisado em termos e a consulta é montada por
 * código, então nenhuma entrada vira sintaxe de consulta nem lança exceção de parse.
 */
public final class LuceneSearchIndex implements SearchIndex, AutoCloseable {

    private static final String F_CHUNK_ID = "chunkId";
    private static final String F_DOC_ID = "docId";
    private static final String F_SOURCE = "source";
    private static final String F_LANGUAGE = "language";
    private static final String F_VERSION = "version";
    private static final String F_TEXT = "text";
    private static final String F_EXACT = "exact";
    private static final String F_VECTOR = "vector";
    private static final String S_LANGUAGE = "languageLabel";
    private static final String S_VERSION = "versionLabel";
    private static final String S_HEADING = "headingPath";
    private static final String S_BODY = "body";
    private static final String S_ORDINAL = "ordinal";
    private static final String S_REVIEWED = "reviewed";

    private static final float EXACT_BOOST = 2f;
    /** Teto de termos distintos por consulta: protege contra consulta gigante (o Lucene limita a 1024 cláusulas). */
    private static final int MAX_QUERY_TERMS = 64;

    private static final Logger LOG = LoggerFactory.getLogger(LuceneSearchIndex.class);

    private final MarkdownChunker chunker;
    private final IndexReadiness readiness;
    private final int searchDepth;
    private final Optional<EmbeddingModel> embeddings;
    private final HybridRanker ranker = new HybridRanker();
    private final Analyzer textAnalyzer = textAnalyzer();
    private final Analyzer exactAnalyzer = exactAnalyzer();
    private final ByteBuffersDirectory directory = new ByteBuffersDirectory();
    private final IndexWriter writer;
    private final SearcherManager searchers;
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    private final Map<String, DocInfo> infos = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    /** Índice só com BM25. */
    public LuceneSearchIndex(RetrievalProperties properties, IndexReadiness readiness) {
        this(properties, readiness, Optional.empty());
    }

    public LuceneSearchIndex(
            RetrievalProperties properties, IndexReadiness readiness, Optional<EmbeddingModel> embeddings) {
        this.chunker = new MarkdownChunker(properties.maxChunkChars());
        this.readiness = readiness;
        this.searchDepth = properties.searchDepth();
        this.embeddings = embeddings;
        try {
            // Sem o wrapper o writer indexaria "exact" com o analisador de "text" e só a consulta usaria o de espaço.
            Analyzer indexAnalyzer = new PerFieldAnalyzerWrapper(textAnalyzer, Map.of(F_EXACT, exactAnalyzer));
            IndexWriterConfig config = new IndexWriterConfig(indexAnalyzer).setCommitOnClose(false);
            this.writer = new IndexWriter(directory, config);
            this.searchers = new SearcherManager(writer, null);
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao abrir o índice em memória", e);
        }
    }

    private static Analyzer textAnalyzer() {
        return new Analyzer() {
            @Override
            protected TokenStreamComponents createComponents(String fieldName) {
                StandardTokenizer source = new StandardTokenizer();
                TokenStream chain = new ASCIIFoldingFilter(new LowerCaseFilter(source));
                return new TokenStreamComponents(source, chain);
            }
        };
    }

    private static Analyzer exactAnalyzer() {
        return new Analyzer() {
            @Override
            protected TokenStreamComponents createComponents(String fieldName) {
                WhitespaceTokenizer source = new WhitespaceTokenizer();
                return new TokenStreamComponents(source, new LowerCaseFilter(source));
            }
        };
    }

    @Override
    public void upsert(Document doc) {
        upsert(doc, true);
    }

    @Override
    public void upsertDeferred(Document doc) {
        upsert(doc, false);
    }

    @Override
    public void refresh() {
        try {
            searchers.maybeRefreshBlocking();
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao atualizar o leitor do índice", e);
        }
    }

    private void upsert(Document doc, boolean refreshNow) {
        DocumentMetadata meta = doc.metadata();
        String docId = meta.id().value();
        List<org.apache.lucene.document.Document> chunks = toLuceneDocs(doc);
        synchronized (lockFor(docId)) {
            try {
                if (chunks.isEmpty()) {
                    writer.deleteDocuments(new Term(F_DOC_ID, docId));
                    infos.remove(docId);
                } else {
                    // Uma operação só: o Lucene troca os trechos antigos pelos novos sem estado intermediário visível.
                    writer.updateDocuments(new Term(F_DOC_ID, docId), chunks);
                    infos.put(docId, new DocInfo(lower(meta.language()), meta.version()));
                }
                if (refreshNow) {
                    searchers.maybeRefreshBlocking();
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Falha ao indexar " + meta.id().logRef(), e);
            }
        }
    }

    @Override
    public void remove(DocumentId id) {
        String docId = id.value();
        synchronized (lockFor(docId)) {
            try {
                writer.deleteDocuments(new Term(F_DOC_ID, docId));
                infos.remove(docId);
                searchers.maybeRefreshBlocking();
            } catch (IOException e) {
                throw new UncheckedIOException("Falha ao remover " + id.logRef(), e);
            }
        }
    }

    @Override
    public List<Chunk> search(String query, SearchFilter filter, int limit) {
        if (query == null || limit <= 0) {
            return List.of();
        }
        Query scoring = scoringQuery(query);
        if (scoring == null) {
            return List.of();
        }
        Query filterQuery = filterQuery(filter);
        Optional<float[]> queryVector = embeddings.flatMap(model -> embedQuery(model, query));
        if (queryVector.isEmpty()) {
            return run(bm25Query(scoring, filterQuery), limit);
        }
        int wanted = Math.max(searchDepth, limit);
        List<Chunk> bm25 = run(bm25Query(scoring, filterQuery), wanted);
        List<Chunk> nearest =
                runVector(new KnnFloatVectorQuery(F_VECTOR, queryVector.get(), wanted, filterQuery), wanted);
        return fuse(bm25, nearest, limit);
    }

    private static Query bm25Query(Query scoring, Query filterQuery) {
        return new BooleanQuery.Builder()
                .add(scoring, Occur.MUST)
                .add(filterQuery, Occur.FILTER)
                .build();
    }

    private List<Chunk> fuse(List<Chunk> bm25, List<Chunk> nearest, int limit) {
        Map<String, Chunk> byId = new LinkedHashMap<>();
        bm25.forEach(c -> byId.putIfAbsent(c.id(), c));
        nearest.forEach(c -> byId.putIfAbsent(c.id(), c));
        List<String> fused = ranker.fuse(
                bm25.stream().map(Chunk::id).toList(),
                nearest.stream().map(Chunk::id).toList(),
                limit);
        return fused.stream().map(byId::get).toList();
    }

    /** Vetor da consulta, ou vazio se o modelo falhou (a busca cai para BM25; o log leva só a classe do erro). */
    private static Optional<float[]> embedQuery(EmbeddingModel model, String query) {
        try {
            return usable(model.embedQuery(query));
        } catch (RuntimeException e) {
            LOG.warn(
                    "Embedding da consulta falhou ({}); busca só com BM25",
                    e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** Vetor nulo, com valor não finito ou sem direção (só zeros) não serve ao cosseno. */
    private static Optional<float[]> usable(float[] vector) {
        boolean nonZero = false;
        for (float x : vector) {
            if (!Float.isFinite(x)) {
                return Optional.empty();
            }
            nonZero |= x != 0f;
        }
        return nonZero ? Optional.of(vector) : Optional.empty();
    }

    @Override
    public List<Chunk> chunksOf(DocumentId id) {
        List<Chunk> chunks = new ArrayList<>(runAll(new TermQuery(new Term(F_DOC_ID, id.value()))));
        chunks.sort(Comparator.comparingInt(Chunk::ordinal));
        return chunks;
    }

    @Override
    public SortedSet<String> versionsOf(String language) {
        String wanted = lower(language);
        SortedSet<String> versions = new TreeSet<>(VersionOrder.INSTANCE);
        for (DocInfo info : infos.values()) {
            if (info.language().equals(wanted)) {
                info.version().ifPresent(versions::add);
            }
        }
        return versions;
    }

    @Override
    public boolean isReady() {
        return readiness.isReady();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            searchers.close();
            writer.close();
            directory.close();
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao fechar o índice", e);
        } finally {
            textAnalyzer.close();
            exactAnalyzer.close();
        }
    }

    private Object lockFor(String docId) {
        return locks.computeIfAbsent(docId, key -> new Object());
    }

    private List<org.apache.lucene.document.Document> toLuceneDocs(Document doc) {
        DocumentMetadata meta = doc.metadata();
        String docId = meta.id().value();
        String ref = meta.id().logRef();
        List<org.apache.lucene.document.Document> out = new ArrayList<>();
        int ordinal = 0;
        for (MarkdownChunker.Section section : chunker.split(doc.body())) {
            org.apache.lucene.document.Document d = new org.apache.lucene.document.Document();
            d.add(new StringField(F_CHUNK_ID, ref + "-" + ordinal, Field.Store.YES));
            d.add(new StringField(F_DOC_ID, docId, Field.Store.YES));
            d.add(new StringField(F_SOURCE, meta.source().label(), Field.Store.YES));
            d.add(new StringField(F_LANGUAGE, lower(meta.language()), Field.Store.NO));
            d.add(new StoredField(S_LANGUAGE, meta.language()));
            meta.version().ifPresent(v -> {
                d.add(new StringField(F_VERSION, lower(v), Field.Store.NO));
                d.add(new StoredField(S_VERSION, v));
            });
            d.add(new StoredField(S_HEADING, section.headingPath()));
            d.add(new StoredField(S_BODY, section.text()));
            d.add(new StoredField(S_ORDINAL, ordinal));
            d.add(new StoredField(S_REVIEWED, meta.reviewed() ? 1 : 0));
            String searchable = section.headingPath() + "\n" + section.text();
            d.add(new TextField(F_TEXT, searchable, Field.Store.NO));
            d.add(new TextField(F_EXACT, searchable, Field.Store.NO));
            embedPassage(searchable, ref)
                    .ifPresent(v -> d.add(new KnnFloatVectorField(F_VECTOR, v, VectorSimilarityFunction.COSINE)));
            out.add(d);
            ordinal++;
        }
        return out;
    }

    /** Vetor do trecho; vazio sem modelo ou se ele falhar (fica só no BM25; o log leva a referência curta). */
    private Optional<float[]> embedPassage(String searchable, String ref) {
        if (embeddings.isEmpty()) {
            return Optional.empty();
        }
        try {
            return usable(embeddings.get().embedPassage(searchable));
        } catch (RuntimeException e) {
            LOG.warn(
                    "Embedding do trecho de {} falhou ({}); indexado só no BM25",
                    ref,
                    e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** {@code null} quando a consulta não tem nenhum termo analisável. */
    private Query scoringQuery(String raw) {
        BooleanQuery.Builder bool = new BooleanQuery.Builder();
        boolean any = false;
        for (String term : terms(textAnalyzer, F_TEXT, raw)) {
            bool.add(new TermQuery(new Term(F_TEXT, term)), Occur.SHOULD);
            any = true;
        }
        for (String term : terms(exactAnalyzer, F_EXACT, raw)) {
            bool.add(new BoostQuery(new TermQuery(new Term(F_EXACT, term)), EXACT_BOOST), Occur.SHOULD);
            any = true;
        }
        return any ? bool.build() : null;
    }

    private static Set<String> terms(Analyzer analyzer, String field, String raw) {
        Set<String> terms = new LinkedHashSet<>();
        try (TokenStream stream = analyzer.tokenStream(field, raw)) {
            CharTermAttribute attribute = stream.addAttribute(CharTermAttribute.class);
            stream.reset();
            while (terms.size() < MAX_QUERY_TERMS && stream.incrementToken()) {
                terms.add(attribute.toString());
            }
            stream.end();
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao analisar a consulta", e);
        }
        return terms;
    }

    /**
     * (fonte=PUBLIC E [linguagem] E [versão em versions]) OU (fonte=INTERNAL). Cada ramo só existe se a fonte está em
     * {@code sources} (vazio vale as duas). Linguagem e versão restringem só o público: documento interno não tem
     * versão e é filtrado apenas por estar ou não nas fontes pedidas.
     */
    private static Query filterQuery(SearchFilter filter) {
        boolean anySource = filter.sources().isEmpty();
        BooleanQuery.Builder branches = new BooleanQuery.Builder();
        if (anySource || filter.sources().contains(Source.PUBLIC)) {
            BooleanQuery.Builder pub = new BooleanQuery.Builder()
                    .add(new TermQuery(new Term(F_SOURCE, Source.PUBLIC.label())), Occur.FILTER);
            filter.language()
                    .filter(l -> !l.isBlank())
                    .ifPresent(l -> pub.add(new TermQuery(new Term(F_LANGUAGE, lower(l))), Occur.FILTER));
            if (!filter.versions().isEmpty()) {
                List<BytesRef> versions = filter.versions().stream()
                        .map(LuceneSearchIndex::lower)
                        .distinct()
                        .map(BytesRef::new)
                        .toList();
                pub.add(new TermInSetQuery(F_VERSION, versions), Occur.FILTER);
            }
            branches.add(pub.build(), Occur.SHOULD);
        }
        if (anySource || filter.sources().contains(Source.INTERNAL)) {
            branches.add(new TermQuery(new Term(F_SOURCE, Source.INTERNAL.label())), Occur.SHOULD);
        }
        return branches.build();
    }

    private List<Chunk> run(Query query, int limit) {
        IndexSearcher searcher = acquire();
        try {
            ScoreDoc[] hits = searcher.search(query, limit).scoreDocs;
            return read(searcher, hits);
        } catch (IOException e) {
            throw new UncheckedIOException("Falha na busca", e);
        } finally {
            release(searcher);
        }
    }

    private List<Chunk> runVector(Query knn, int n) {
        IndexSearcher searcher = acquire();
        try {
            return read(searcher, searcher.search(knn, n).scoreDocs);
        } catch (IOException e) {
            throw new UncheckedIOException("Falha na busca vetorial", e);
        } finally {
            release(searcher);
        }
    }

    private List<Chunk> runAll(Query query) {
        IndexSearcher searcher = acquire();
        try {
            int total = searcher.count(query);
            if (total == 0) {
                return List.of();
            }
            return read(searcher, searcher.search(query, total).scoreDocs);
        } catch (IOException e) {
            throw new UncheckedIOException("Falha na leitura dos trechos", e);
        } finally {
            release(searcher);
        }
    }

    private static List<Chunk> read(IndexSearcher searcher, ScoreDoc[] hits) throws IOException {
        List<Chunk> chunks = new ArrayList<>(hits.length);
        for (ScoreDoc hit : hits) {
            chunks.add(toChunk(searcher.storedFields().document(hit.doc)));
        }
        return chunks;
    }

    private static Chunk toChunk(org.apache.lucene.document.Document d) {
        String version = d.get(S_VERSION);
        return new Chunk(
                d.get(F_CHUNK_ID),
                DocumentId.of(d.get(F_DOC_ID)),
                Source.fromLabel(d.get(F_SOURCE)).orElseThrow(),
                d.get(S_LANGUAGE),
                Optional.ofNullable(version),
                d.get(S_HEADING),
                d.get(S_BODY),
                d.getField(S_ORDINAL).numericValue().intValue(),
                d.getField(S_REVIEWED).numericValue().intValue() == 1);
    }

    private IndexSearcher acquire() {
        try {
            return searchers.acquire();
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao abrir o leitor do índice", e);
        }
    }

    private void release(IndexSearcher searcher) {
        try {
            searchers.release(searcher);
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao liberar o leitor do índice", e);
        }
    }

    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    private record DocInfo(String language, Optional<String> version) {}
}
