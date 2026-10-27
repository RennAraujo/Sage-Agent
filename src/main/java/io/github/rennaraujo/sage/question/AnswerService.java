package io.github.rennaraujo.sage.question;

import io.github.rennaraujo.sage.audit.AuditEvent;
import io.github.rennaraujo.sage.audit.AuditEventType;
import io.github.rennaraujo.sage.audit.AuditException;
import io.github.rennaraujo.sage.audit.AuditLog;
import io.github.rennaraujo.sage.document.Source;
import io.github.rennaraujo.sage.question.guard.AnswerGuard;
import io.github.rennaraujo.sage.question.llm.LanguageModel;
import io.github.rennaraujo.sage.question.llm.ModelEvent;
import io.github.rennaraujo.sage.question.llm.ModelProperties;
import io.github.rennaraujo.sage.question.llm.ModelTier;
import io.github.rennaraujo.sage.question.llm.Prompt;
import io.github.rennaraujo.sage.question.llm.PromptBuilder;
import io.github.rennaraujo.sage.search.Chunk;
import io.github.rennaraujo.sage.search.Retriever;
import io.github.rennaraujo.sage.search.ScoredChunk;
import io.github.rennaraujo.sage.search.SearchFilter;
import io.github.rennaraujo.sage.search.SearchIndex;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Responde uma pergunta, nesta ordem: analisa e resolve a versão, busca, decide a rota, grava {@code QUESTION_ASKED}
 * na auditoria, emite o {@code metadata}, faz o stream do modelo passando cada pedaço pelo guard, grava
 * {@code QUESTION_ANSWERED} e só então emite o {@code done}.
 *
 * <p>A auditoria é fail-closed: se o {@code QUESTION_ASKED} não grava, nada é emitido e a exceção sobe (o controller a
 * transforma em evento {@code error}). O {@code QUESTION_ANSWERED} é gravado em qualquer desfecho: {@code completed},
 * {@code interrupted} (o stream do modelo começou e foi cortado por erro, prazo ou cliente que saiu, ou o cliente saiu
 * antes) ou {@code failed} (erro antes de o modelo começar). Se ele não grava depois de uma resposta completa, o
 * cliente recebe {@code error} em vez de {@code done}.
 *
 * <p>A versão da documentação usada é dita pelo código, nunca pelo modelo: "Documentação consultada: Java 21" antes da
 * primeira frase aprovada que cita um trecho daquela linguagem e versão (só fontes de fato citadas aparecem), e
 * "Considerei a versão 25, a mais recente disponível." quando a pergunta não trouxe versão. Versão pedida que não está
 * no acervo vira "Não tenho a documentação do Java 8 no acervo."; numa pergunta só pública isso encerra a resposta sem
 * chamar o modelo, numa mista o lado interno segue.
 *
 * <p>O modelo tem um prazo total ({@code sage.models.timeout}), não por token, e o stream é cancelado assim que o
 * cliente vai embora ({@link AnswerSink#onClose}), para o Bedrock parar de gerar.
 *
 * <p>Nada de pergunta, trecho ou resposta vai para log ou auditoria: só rota, modelo, tempos e referências.
 */
public final class AnswerService {

    static final String NO_CONTEXT = "Não encontrei isso na documentação disponível.";
    static final String INFERRED_VERSION = "Considerei a versão %s, a mais recente disponível.";
    static final String CONSULTED = "Documentação consultada: %s";
    static final String MISSING_VERSION = "Não tenho a documentação do %s %s no acervo.";
    static final String NO_VERSION = "(versão não informada)";

    private static final Logger LOG = LoggerFactory.getLogger(AnswerService.class);

    private final QuestionAnalyzer analyzer;
    private final AliasCatalog catalog;
    private final Retriever retriever;
    private final SearchIndex index;
    private final RouteDecider decider;
    private final PromptBuilder promptBuilder;
    private final LanguageModel languageModel;
    private final AuditLog audit;
    private final ModelProperties models;
    private final Clock clock;

    public AnswerService(
            QuestionAnalyzer analyzer,
            AliasCatalog catalog,
            Retriever retriever,
            SearchIndex index,
            RouteDecider decider,
            PromptBuilder promptBuilder,
            LanguageModel languageModel,
            AuditLog audit,
            ModelProperties models,
            Clock clock) {
        this.analyzer = analyzer;
        this.catalog = catalog;
        this.retriever = retriever;
        this.index = index;
        this.decider = decider;
        this.promptBuilder = promptBuilder;
        this.languageModel = languageModel;
        this.audit = audit;
        this.models = models;
        this.clock = clock;
    }

    /** {@code false} quando não há modelo configurado; o controller responde 503 antes de abrir o stream. */
    public boolean modelAvailable() {
        return languageModel.available();
    }

    public void answer(AnswerRequest request, AnswerSink sink) {
        Timer timer = new Timer();
        Progress progress = new Progress();
        sink.onClose(progress::close);
        String conversationId =
                request.conversationId().orElseGet(() -> UUID.randomUUID().toString());

        Plan plan = plan(request.question());
        timer.lap("analise");
        List<ScoredChunk> chunks =
                plan.skipSearch() ? List.of() : retriever.retrieve(request.question(), plan.filter());
        timer.lap("busca");
        Route route = decider.decide(plan.analysis(), chunks, plan.missing());

        recordAsked(request.actor(), conversationId, route, chunks);
        Answered answered;
        try {
            answered = route == Route.NOT_DOCUMENTED
                    ? notDocumented(plan, conversationId, chunks, timer, sink)
                    : withModel(request, plan, conversationId, chunks, timer, sink, progress);
        } catch (RuntimeException e) {
            String outcome = interrupted(e, progress) ? "interrupted" : "failed";
            try {
                recordAnswered(request.actor(), conversationId, route, outcome, progress);
            } catch (AuditException auditFailure) {
                e.addSuppressed(auditFailure);
            }
            LOG.warn(
                    "Pergunta não concluída: rota={} desfecho={} falha={} tempos={}",
                    route,
                    outcome,
                    e.getClass().getName(),
                    timer.timings());
            throw e;
        }
        recordAnswered(request.actor(), conversationId, route, "completed", progress);
        sink.done(answered.metadata());
        LOG.info(
                "Pergunta respondida: rota={} modelo={} topScore={} trechos={} descartadas={} tempos={} refs={}",
                route,
                answered.metadata().model().map(Enum::name).orElse("nenhum"),
                String.format(Locale.ROOT, "%.3f", RouteDecider.topScore(chunks)),
                chunks.size(),
                answered.metadata().droppedSentences(),
                answered.metadata().timingsMillis(),
                chunks.stream().map(c -> c.chunk().documentId().logRef()).toList());
    }

    /** Análise, versão inferida, versões faltantes e o filtro de busca resultante. */
    private record Plan(
            QuestionAnalysis analysis,
            Optional<String> inferred,
            List<String> missing,
            SearchFilter filter,
            Optional<String> versionUsed) {

        boolean skipSearch() {
            return RouteDecider.publicOnly(analysis) && !missing.isEmpty();
        }
    }

    private Plan plan(String question) {
        QuestionAnalysis analysis = analyzer.analyze(question, Optional.empty());
        Optional<String> inferred = inferVersion(analysis);
        List<String> missing = decider.missingVersions(analysis);
        List<String> versions = inferred.map(List::of).orElse(analysis.versions());
        Set<Source> sources = EnumSet.copyOf(analysis.sources());
        if (!missing.isEmpty() && !RouteDecider.publicOnly(analysis)) {
            // Pergunta mista: o lado público fica só com as versões que existem; sem nenhuma, sai da busca.
            versions = versions.stream().filter(v -> !missing.contains(v)).toList();
            if (versions.isEmpty()) {
                sources.remove(Source.PUBLIC);
            }
        }
        boolean singlePublicVersion =
                sources.contains(Source.PUBLIC) && analysis.language().isPresent() && versions.size() == 1;
        return new Plan(
                analysis,
                inferred,
                missing,
                new SearchFilter(sources, analysis.language(), versions),
                singlePublicVersion ? Optional.of(versions.get(0)) : Optional.empty());
    }

    private Answered notDocumented(
            Plan plan, String conversationId, List<ScoredChunk> chunks, Timer timer, AnswerSink sink) {
        AnswerMetadata metadata = new AnswerMetadata(
                conversationId,
                Route.NOT_DOCUMENTED,
                Optional.empty(),
                RouteDecider.topScore(chunks),
                List.of(),
                plan.versionUsed(),
                false,
                0,
                timer.timings());
        sink.metadata(metadata);
        missingNotices(plan).forEach(sink::text);
        if (!plan.skipSearch()) {
            plan.inferred().ifPresent(v -> sink.text(INFERRED_VERSION.formatted(v)));
            sink.text(NO_CONTEXT);
        }
        timer.total();
        return new Answered(withFinal(metadata, 0, timer));
    }

    private Answered withModel(
            AnswerRequest request,
            Plan plan,
            String conversationId,
            List<ScoredChunk> chunks,
            Timer timer,
            AnswerSink sink,
            Progress progress) {
        List<Citation> citations = citations(chunks);
        AnswerMetadata metadata = new AnswerMetadata(
                conversationId,
                Route.FAST_MODEL,
                Optional.of(ModelTier.FAST),
                RouteDecider.topScore(chunks),
                citations,
                plan.versionUsed(),
                chunks.stream().anyMatch(c -> !c.chunk().reviewed()),
                0,
                timer.timings());
        sink.metadata(metadata);
        missingNotices(plan).forEach(sink::text);
        plan.inferred().ifPresent(v -> sink.text(INFERRED_VERSION.formatted(v)));

        Prompt prompt =
                promptBuilder.build(request.question(), chunks, plan.analysis().type(), false);
        AnswerGuard guard = new AnswerGuard(chunks.size());
        progress.start(guard, citations);
        Emitter emitter = new Emitter(sink, chunks);
        AtomicBoolean deadline = new AtomicBoolean();
        if (progress.clientGone()) {
            throw new AnswerAbortedException(AnswerAbortedException.Reason.CLIENT_GONE);
        }
        languageModel.stream(ModelTier.FAST, prompt)
                .takeUntilOther(progress.closed())
                .takeUntilOther(Mono.delay(models.timeout()).doOnNext(tick -> deadline.set(true)))
                .doOnNext(event -> handle(event, guard, emitter))
                .blockLast();
        if (progress.clientGone()) {
            throw new AnswerAbortedException(AnswerAbortedException.Reason.CLIENT_GONE);
        }
        if (deadline.get()) {
            throw new AnswerAbortedException(AnswerAbortedException.Reason.MODEL_DEADLINE);
        }
        guard.finish().forEach(emitter::accepted);
        timer.lap("modelo");
        timer.total();
        return new Answered(withFinal(metadata, guard.dropped(), timer));
    }

    private static void handle(ModelEvent event, AnswerGuard guard, Emitter emitter) {
        switch (event) {
            case ModelEvent.TextDelta delta -> guard.accept(delta.text()).forEach(emitter::accepted);
            case ModelEvent.EscalationRequested ignored ->
                // allowEscalation=false aqui; o pedido é ignorado até a Task 12 ligar a ferramenta.
                LOG.debug("Pedido de escalonamento ignorado");
            case ModelEvent.Completed ignored -> {
                // Nada a fazer: o finish() do guard roda depois do stream.
            }
        }
    }

    /** Emite as frases aprovadas, precedidas da linha de versão de cada fonte que elas citam pela primeira vez. */
    private final class Emitter {
        private final AnswerSink sink;
        private final List<ScoredChunk> chunks;
        private final Set<String> announced = new HashSet<>();

        Emitter(AnswerSink sink, List<ScoredChunk> chunks) {
            this.sink = sink;
            this.chunks = chunks;
        }

        void accepted(String text) {
            for (int n : AnswerGuard.citationsIn(text)) {
                if (n >= 1 && n <= chunks.size()) {
                    String line = CONSULTED.formatted(label(chunks.get(n - 1).chunk()));
                    if (announced.add(line)) {
                        sink.text(line);
                    }
                }
            }
            sink.text(text);
        }
    }

    private String label(Chunk chunk) {
        if (chunk.source() == Source.INTERNAL) {
            return chunk.language() + " (interna)";
        }
        return catalog.displayName(chunk.language()) + " " + chunk.version().orElse(NO_VERSION);
    }

    private List<String> missingNotices(Plan plan) {
        if (plan.missing().isEmpty() || plan.analysis().language().isEmpty()) {
            return List.of();
        }
        String name = catalog.displayName(plan.analysis().language().get());
        return plan.missing().stream()
                .map(v -> MISSING_VERSION.formatted(name, v))
                .toList();
    }

    private Optional<String> inferVersion(QuestionAnalysis analysis) {
        if (!analysis.sources().contains(Source.PUBLIC)
                || analysis.language().isEmpty()
                || !analysis.versions().isEmpty()) {
            return Optional.empty();
        }
        SortedSet<String> available = index.versionsOf(analysis.language().get());
        return available.isEmpty() ? Optional.empty() : Optional.of(available.last());
    }

    private static List<Citation> citations(List<ScoredChunk> chunks) {
        List<Citation> citations = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            Chunk chunk = chunks.get(i).chunk();
            citations.add(new Citation(
                    i + 1,
                    chunk.documentId(),
                    chunk.headingPath(),
                    chunk.reviewed(),
                    chunk.language(),
                    chunk.version()));
        }
        return citations;
    }

    private static boolean interrupted(RuntimeException e, Progress progress) {
        return progress.modelStarted()
                || e instanceof AnswerAbortedException
                || e instanceof UncheckedIOException
                || progress.clientGone();
    }

    private void recordAsked(String actor, String conversationId, Route route, List<ScoredChunk> chunks) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("conversationId", conversationId);
        details.put("outcome", "started");
        details.put("route", route.name());
        details.put("model", route == Route.FAST_MODEL ? ModelTier.FAST.name() : "none");
        details.put(
                "contextDocIds",
                String.join(
                        ",",
                        chunks.stream()
                                .map(c -> c.chunk().documentId().value())
                                .distinct()
                                .toList()));
        audit.record(new AuditEvent(AuditEventType.QUESTION_ASKED, actor, clock.instant(), details));
    }

    private void recordAnswered(String actor, String conversationId, Route route, String outcome, Progress progress) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("conversationId", conversationId);
        details.put("outcome", outcome);
        details.put("route", route.name());
        details.put("citedDocIds", String.join(",", progress.citedDocIds()));
        details.put("droppedSentences", Integer.toString(progress.dropped()));
        audit.record(new AuditEvent(AuditEventType.QUESTION_ANSWERED, actor, clock.instant(), details));
    }

    private static AnswerMetadata withFinal(AnswerMetadata m, int dropped, Timer timer) {
        return new AnswerMetadata(
                m.conversationId(),
                m.route(),
                m.model(),
                m.topScore(),
                m.citations(),
                m.versionUsed(),
                m.unreviewedSources(),
                dropped,
                timer.timings());
    }

    private record Answered(AnswerMetadata metadata) {}

    /**
     * Estado compartilhado entre a thread da resposta e quem avisa que o cliente saiu (o callback do emitter roda em
     * outra thread). O guard só é lido pela thread da resposta.
     */
    private static final class Progress {
        private final Sinks.One<Boolean> closed = Sinks.one();
        private final AtomicBoolean gone = new AtomicBoolean();
        private volatile AnswerGuard guard;
        private volatile List<Citation> citations = List.of();

        void close() {
            gone.set(true);
            closed.tryEmitValue(Boolean.TRUE);
        }

        boolean clientGone() {
            return gone.get();
        }

        Mono<Boolean> closed() {
            return closed.asMono();
        }

        void start(AnswerGuard started, List<Citation> context) {
            this.citations = context;
            this.guard = started;
        }

        boolean modelStarted() {
            return guard != null;
        }

        List<String> citedDocIds() {
            AnswerGuard current = guard;
            if (current == null) {
                return List.of();
            }
            return current.cited().stream()
                    .map(n -> citations.get(n - 1).documentId().value())
                    .distinct()
                    .toList();
        }

        int dropped() {
            AnswerGuard current = guard;
            return current == null ? 0 : current.dropped();
        }
    }

    /** Tempos por etapa em milissegundos, medidos com {@link System#nanoTime()}. */
    private static final class Timer {
        private final long start = System.nanoTime();
        private final Map<String, Long> timings = new LinkedHashMap<>();
        private long last = start;

        void lap(String name) {
            long now = System.nanoTime();
            timings.put(name, TimeUnit.NANOSECONDS.toMillis(now - last));
            last = now;
        }

        void total() {
            timings.put("total", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        }

        Map<String, Long> timings() {
            return new LinkedHashMap<>(timings);
        }
    }
}
