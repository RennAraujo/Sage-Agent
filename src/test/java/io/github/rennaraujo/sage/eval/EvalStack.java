package io.github.rennaraujo.sage.eval;

import com.github.benmanes.caffeine.cache.Ticker;
import io.github.rennaraujo.sage.audit.AuditEvent;
import io.github.rennaraujo.sage.audit.AuditLog;
import io.github.rennaraujo.sage.document.Document;
import io.github.rennaraujo.sage.gap.GapRegistry;
import io.github.rennaraujo.sage.question.AliasCatalog;
import io.github.rennaraujo.sage.question.AnswerCache;
import io.github.rennaraujo.sage.question.AnswerMetadata;
import io.github.rennaraujo.sage.question.AnswerRequest;
import io.github.rennaraujo.sage.question.AnswerService;
import io.github.rennaraujo.sage.question.AnswerSink;
import io.github.rennaraujo.sage.question.CacheProperties;
import io.github.rennaraujo.sage.question.ConversationProperties;
import io.github.rennaraujo.sage.question.ConversationStore;
import io.github.rennaraujo.sage.question.FlowContextExpander;
import io.github.rennaraujo.sage.question.FlowProperties;
import io.github.rennaraujo.sage.question.QuestionAnalyzer;
import io.github.rennaraujo.sage.question.RerankQueryTextCleaner;
import io.github.rennaraujo.sage.question.RouteDecider;
import io.github.rennaraujo.sage.question.guard.AnswerGuardFactory;
import io.github.rennaraujo.sage.question.guard.GroundednessChecker;
import io.github.rennaraujo.sage.question.guard.GuardProperties;
import io.github.rennaraujo.sage.question.guard.NliFiles;
import io.github.rennaraujo.sage.question.guard.OnnxNliChecker;
import io.github.rennaraujo.sage.question.llm.LanguageModel;
import io.github.rennaraujo.sage.question.llm.ModelProperties;
import io.github.rennaraujo.sage.question.llm.PromptBuilder;
import io.github.rennaraujo.sage.search.IndexReadiness;
import io.github.rennaraujo.sage.search.LuceneSearchIndex;
import io.github.rennaraujo.sage.search.RetrievalProperties;
import io.github.rennaraujo.sage.search.Retriever;
import io.github.rennaraujo.sage.search.embedding.EmbeddingModel;
import io.github.rennaraujo.sage.search.embedding.ModelFiles;
import io.github.rennaraujo.sage.search.embedding.OnnxEmbeddingModel;
import io.github.rennaraujo.sage.search.rerank.OnnxReranker;
import io.github.rennaraujo.sage.search.rerank.RerankProperties;
import io.github.rennaraujo.sage.search.rerank.Reranker;
import io.github.rennaraujo.sage.upload.SecretScanner;
import io.github.rennaraujo.sage.web.WebFallback;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.mockito.Mockito;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Tudo o que a avaliação precisa, montado uma vez por JVM: o acervo de {@code eval/acervo} indexado, o reranker, o
 * catálogo de aliases e o analisador. Os números de configuração (limiar, candidatos, tokens) são lidos do
 * {@code application.yml} de produção, para o teste medir o que de fato vai ao ar. Sem os arquivos dos modelos ONNX o
 * conjunto degrada como a aplicação: só BM25 e sem reranker.
 */
public final class EvalStack {

    private static final String ACTOR = "avaliador";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-27T12:00:00Z"), ZoneOffset.UTC);
    private static final Duration MODEL_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_MODEL_TOKENS = 1500;
    private static final int CACHE_ENTRIES = 1000;
    private static final int CONVERSATIONS = 10_000;
    private static final int TURNS = 5;
    private static EvalStack instance;

    private final Path modelsDir = Path.of(System.getProperty("sage.models.dir", "models"));
    private final RetrievalProperties retrieval;
    private final RerankProperties rerank;
    private final GuardProperties guard;
    private final FlowProperties flow = new FlowProperties(12000, 3, 24000);
    private final List<Document> documents;
    private final Optional<OnnxEmbeddingModel> embeddings;
    private final Optional<OnnxReranker> reranker;
    private final LuceneSearchIndex index;
    private final Retriever retriever;
    private final AliasCatalog catalog;
    private final QuestionAnalyzer analyzer;
    private OnnxNliChecker nli;

    private EvalStack(
            int depthOverride, int candidatesOverride, int rerankTokensOverride, boolean longChunks, boolean hook) {
        Map<String, Object> sage = sageSettings();
        RetrievalProperties fromFile = retrievalFrom(section(sage, "retrieval"));
        this.retrieval = new RetrievalProperties(
                fromFile.maxChunkChars(),
                candidatesOverride > 0 ? candidatesOverride : fromFile.candidates(),
                fromFile.contextChunks(),
                fromFile.minScore(),
                fromFile.minScoreWithoutReranker(),
                fromFile.comparisonMaxChunks(),
                depthOverride > 0 ? depthOverride : fromFile.searchDepth());
        int tokens = rerankTokensOverride > 0 ? rerankTokensOverride : integer(section(sage, "rerank"), "max-tokens");
        this.rerank = new RerankProperties(tokens);
        this.guard = guardFrom(section(sage, "guard"));
        this.documents = longChunks ? EvalAcervo.loadWithLongChunks() : EvalAcervo.load();
        ModelFiles embeddingFiles = ModelFiles.inDir(modelsDir);
        ModelFiles rerankerFiles = ModelFiles.rerankerInDir(modelsDir);
        if (EvalModels.strict() && !(embeddingFiles.available() && rerankerFiles.available())) {
            throw new IllegalStateException("-Peval exige os modelos de embeddings e de reranking em " + modelsDir);
        }
        this.embeddings =
                embeddingFiles.available() ? Optional.of(new OnnxEmbeddingModel(embeddingFiles)) : Optional.empty();
        this.reranker =
                rerankerFiles.available() ? Optional.of(new OnnxReranker(rerankerFiles, rerank)) : Optional.empty();
        this.index = new LuceneSearchIndex(retrieval, new IndexReadiness(), embeddings.map(EmbeddingModel.class::cast));
        documents.forEach(index::upsertDeferred);
        index.refresh();
        this.catalog = new AliasCatalog(new EvalAcervo.Store(documents));
        this.retriever = new Retriever(
                index, reranker.map(Reranker.class::cast), retrieval, new RerankQueryTextCleaner(catalog));
        this.analyzer = new QuestionAnalyzer(catalog);
        if (hook) {
            Runtime.getRuntime().addShutdownHook(new Thread(this::close, "eval-stack-close"));
        }
    }

    /**
     * Conjunto com outra profundidade de busca, outro número de candidatos e de tokens do reranker (e, opcionalmente,
     * trechos no teto de 1500 caracteres), para a grade de calibração; feche com {@link #close()}.
     */
    public static EvalStack custom(int searchDepth, int candidates, int rerankMaxTokens, boolean longChunks) {
        return new EvalStack(searchDepth, candidates, rerankMaxTokens, longChunks, false);
    }

    public static synchronized EvalStack get() {
        if (instance == null) {
            instance = new EvalStack(0, 0, 0, false, true);
        }
        return instance;
    }

    public synchronized void close() {
        index.close();
        reranker.ifPresent(OnnxReranker::close);
        embeddings.ifPresent(OnnxEmbeddingModel::close);
        if (nli != null) {
            nli.close();
        }
    }

    public boolean modelsPresent() {
        return embeddings.isPresent() && reranker.isPresent();
    }

    public boolean nliPresent() {
        return NliFiles.inDir(modelsDir).available();
    }

    public Path modelsDir() {
        return modelsDir;
    }

    public RetrievalProperties retrieval() {
        return retrieval;
    }

    public RerankProperties rerank() {
        return rerank;
    }

    public GuardProperties guard() {
        return guard;
    }

    public List<Document> documents() {
        return documents;
    }

    public LuceneSearchIndex index() {
        return index;
    }

    public Retriever retriever() {
        return retriever;
    }

    public AliasCatalog catalog() {
        return catalog;
    }

    public QuestionAnalyzer analyzer() {
        return analyzer;
    }

    /** O verificador de NLI real, aberto na primeira chamada e compartilhado (a sessão pesa 1,1 GB). */
    public synchronized OnnxNliChecker nli() {
        if (nli == null) {
            nli = new OnnxNliChecker(NliFiles.inDir(modelsDir), guard);
        }
        return nli;
    }

    /** Só citações e segredos, determinístico; o NLI real entra nos testes que o pedem. */
    public static AnswerGuardFactory citationsOnly() {
        return AnswerGuardFactory.citationsOnly();
    }

    public AnswerGuardFactory withChecker(GroundednessChecker checker) {
        return new AnswerGuardFactory(checker, new SecretScanner(), guard);
    }

    /** Serviço com o limiar de produção ({@code sage.retrieval.min-score}) e o guard só de citações. */
    public Session session(LanguageModel model) {
        return session(model, citationsOnly(), retrieval.minScore());
    }

    public Session session(LanguageModel model, AnswerGuardFactory guards, double minScore) {
        RetrievalProperties tuned = new RetrievalProperties(
                retrieval.maxChunkChars(),
                retrieval.candidates(),
                retrieval.contextChunks(),
                minScore,
                retrieval.minScoreWithoutReranker(),
                retrieval.comparisonMaxChunks());
        RecordingAudit audit = new RecordingAudit();
        AnswerService service = new AnswerService(
                analyzer,
                catalog,
                retriever,
                index,
                new RouteDecider(index, tuned, retriever.rerankerActive()),
                new PromptBuilder(),
                new FlowContextExpander(index, flow),
                model,
                audit,
                new ModelProperties("modelo-rapido", "modelo-forte", MAX_MODEL_TOKENS, 0.0, MODEL_TIMEOUT),
                new AnswerCache(new CacheProperties(Duration.ofHours(1), CACHE_ENTRIES), Ticker.systemTicker()),
                new ConversationStore(
                        new ConversationProperties(Duration.ofMinutes(30), TURNS, CONVERSATIONS),
                        Ticker.systemTicker()),
                Mockito.mock(GapRegistry.class),
                CLOCK,
                WebFallback.NONE,
                guards);
        return new Session(service, audit, model);
    }

    /** Um serviço de resposta pronto, com a auditoria em memória e o modelo que ele usa. */
    public static final class Session {

        private final AnswerService service;
        private final RecordingAudit audit;
        private final LanguageModel model;

        Session(AnswerService service, RecordingAudit audit, LanguageModel model) {
            this.service = service;
            this.audit = audit;
            this.model = model;
        }

        public LanguageModel model() {
            return model;
        }

        public Outcome ask(String question) {
            int before = audit.events.size();
            Sink sink = new Sink();
            if (model instanceof QuotingModel quoting) {
                quoting.resetMark();
            }
            long start = System.nanoTime();
            service.answer(new AnswerRequest(question, Optional.empty(), ACTOR), sink);
            long end = System.nanoTime();
            long modelCall = model instanceof QuotingModel quoting ? quoting.firstCallNanos() : 0;
            long mark = modelCall == 0 ? sink.firstMetadataNanos : Math.min(modelCall, sink.firstMetadataNanos);
            List<AuditEvent> mine = audit.events.subList(before, audit.events.size());
            List<String> contextDocs = mine.stream()
                    .filter(e -> e.details().containsKey("contextDocIds"))
                    .findFirst()
                    .map(e -> e.details().get("contextDocIds"))
                    .filter(s -> !s.isBlank())
                    .map(s -> List.of(s.split(",")))
                    .orElse(List.of());
            return new Outcome(
                    question,
                    List.copyOf(sink.texts),
                    List.copyOf(sink.metadata),
                    sink.done,
                    contextDocs,
                    mark - start,
                    end - start);
        }
    }

    /** O que uma pergunta produziu: eventos {@code text}, os {@code metadata} (o último é o {@code done}) e tempos. */
    public record Outcome(
            String question,
            List<String> texts,
            List<AnswerMetadata> metadata,
            AnswerMetadata done,
            List<String> contextDocs,
            long preModelNanos,
            long totalNanos) {

        public AnswerMetadata first() {
            return metadata.get(0);
        }

        public double preModelMillis() {
            return preModelNanos / 1_000_000.0;
        }
    }

    private static final class Sink implements AnswerSink {
        final List<String> texts = new ArrayList<>();
        final List<AnswerMetadata> metadata = new ArrayList<>();
        AnswerMetadata done;
        long firstMetadataNanos = Long.MAX_VALUE;

        @Override
        public void metadata(AnswerMetadata m) {
            firstMetadataNanos = Math.min(firstMetadataNanos, System.nanoTime());
            metadata.add(m);
        }

        @Override
        public void text(String sentence) {
            texts.add(sentence);
        }

        @Override
        public void done(AnswerMetadata finalMetadata) {
            done = finalMetadata;
        }
    }

    private static final class RecordingAudit implements AuditLog {
        final List<AuditEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void record(AuditEvent event) {
            events.add(event);
        }
    }

    // ------------------------------------------------------------------------ application.yml

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sageSettings() {
        try (InputStream in = new ClassPathResource("application.yml").getInputStream()) {
            Map<String, Object> root = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
            return (Map<String, Object>) root.get("sage");
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> sage, String name) {
        Object value = sage.get(name);
        return value == null ? Map.of() : (Map<String, Object>) value;
    }

    private static int integer(Map<String, Object> section, String key) {
        if (section.get(key) instanceof Number number && number.doubleValue() == number.intValue()) {
            return number.intValue();
        }
        throw new IllegalStateException("application.yml: chave sage.*." + key + " ausente ou não numérica inteira");
    }

    private static double decimal(Map<String, Object> section, String key) {
        if (section.get(key) instanceof Number number) {
            return number.doubleValue();
        }
        throw new IllegalStateException("application.yml: chave sage.*." + key + " ausente ou não numérica");
    }

    private static RetrievalProperties retrievalFrom(Map<String, Object> section) {
        return new RetrievalProperties(
                integer(section, "max-chunk-chars"),
                integer(section, "candidates"),
                integer(section, "context-chunks"),
                decimal(section, "min-score"),
                decimal(section, "min-score-without-reranker"),
                integer(section, "comparison-max-chunks"),
                integer(section, "search-depth"));
    }

    private static GuardProperties guardFrom(Map<String, Object> section) {
        return new GuardProperties(decimal(section, "min-entailment"), integer(section, "nli-max-tokens"), false);
    }
}
