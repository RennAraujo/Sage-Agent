package io.github.rennaraujo.sage.question;

import io.github.rennaraujo.sage.audit.AuditEvent;
import io.github.rennaraujo.sage.audit.AuditEventType;
import io.github.rennaraujo.sage.audit.AuditException;
import io.github.rennaraujo.sage.audit.AuditLog;
import io.github.rennaraujo.sage.document.Document;
import io.github.rennaraujo.sage.document.Source;
import io.github.rennaraujo.sage.gap.GapRegistry;
import io.github.rennaraujo.sage.question.guard.AnswerGuard;
import io.github.rennaraujo.sage.question.llm.LanguageModel;
import io.github.rennaraujo.sage.question.llm.ModelEvent;
import io.github.rennaraujo.sage.question.llm.ModelProperties;
import io.github.rennaraujo.sage.question.llm.ModelTier;
import io.github.rennaraujo.sage.question.llm.Prompt;
import io.github.rennaraujo.sage.question.llm.PromptBuilder;
import io.github.rennaraujo.sage.question.llm.VersionContext;
import io.github.rennaraujo.sage.search.Chunk;
import io.github.rennaraujo.sage.search.Retriever;
import io.github.rennaraujo.sage.search.ScoredChunk;
import io.github.rennaraujo.sage.search.SearchFilter;
import io.github.rennaraujo.sage.search.SearchIndex;
import io.github.rennaraujo.sage.web.WebFallback;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
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
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
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
 * chamar o modelo, a não ser numa comparação em que outra versão pedida existe. Numa pergunta mista o lado interno
 * segue.
 *
 * <p>Comparação entre duas ou mais versões busca cada versão separadamente e vai direto para o modelo forte, que
 * responde versão a versão. Versão sem nenhum trecho recebe do código "A documentação do Java 17 disponível não
 * menciona isso.". O resto começa no modelo rápido, que pode pedir escalada pela ferramenta {@code escalate}.
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
    static final String NOT_MENTIONED = "A documentação do %s disponível não menciona isso.";
    static final String NO_OFFICIAL_SOURCES = "Não há fontes oficiais configuradas para essa linguagem.";
    static final String NO_VERSION = "(versão não informada)";
    static final String ONLY_COMPARED = "Comparei só as versões %s; peça as demais em outra pergunta.";

    /** Piso de trechos por grupo numa comparação; também limita quantos grupos cabem no teto. */
    static final int MIN_CHUNKS_PER_GROUP = 2;

    private static final Logger LOG = LoggerFactory.getLogger(AnswerService.class);

    private final QuestionAnalyzer analyzer;
    private final AliasCatalog catalog;
    private final Retriever retriever;
    private final SearchIndex index;
    private final RouteDecider decider;
    private final PromptBuilder promptBuilder;
    private final FlowContextExpander flowExpander;
    private final LanguageModel languageModel;
    private final AuditLog audit;
    private final ModelProperties models;
    private final AnswerCache cache;
    private final ConversationStore conversations;
    private final GapRegistry gaps;
    private final Clock clock;
    private final WebFallback web;

    /** Sem fallback web. */
    public AnswerService(
            QuestionAnalyzer analyzer,
            AliasCatalog catalog,
            Retriever retriever,
            SearchIndex index,
            RouteDecider decider,
            PromptBuilder promptBuilder,
            FlowContextExpander flowExpander,
            LanguageModel languageModel,
            AuditLog audit,
            ModelProperties models,
            AnswerCache cache,
            ConversationStore conversations,
            GapRegistry gaps,
            Clock clock) {
        this(
                analyzer,
                catalog,
                retriever,
                index,
                decider,
                promptBuilder,
                flowExpander,
                languageModel,
                audit,
                models,
                cache,
                conversations,
                gaps,
                clock,
                WebFallback.NONE);
    }

    public AnswerService(
            QuestionAnalyzer analyzer,
            AliasCatalog catalog,
            Retriever retriever,
            SearchIndex index,
            RouteDecider decider,
            PromptBuilder promptBuilder,
            FlowContextExpander flowExpander,
            LanguageModel languageModel,
            AuditLog audit,
            ModelProperties models,
            AnswerCache cache,
            ConversationStore conversations,
            GapRegistry gaps,
            Clock clock,
            WebFallback web) {
        this.analyzer = analyzer;
        this.catalog = catalog;
        this.retriever = retriever;
        this.index = index;
        this.decider = decider;
        this.promptBuilder = promptBuilder;
        this.flowExpander = flowExpander;
        this.languageModel = languageModel;
        this.audit = audit;
        this.models = models;
        this.cache = cache;
        this.conversations = conversations;
        this.gaps = gaps;
        this.clock = clock;
        this.web = web;
    }

    /** {@code false} quando não há modelo configurado; o controller responde 503 antes de abrir o stream. */
    public boolean modelAvailable() {
        return languageModel.available();
    }

    public void answer(AnswerRequest request, AnswerSink original) {
        // Primeira coisa: uma invalidação durante a análise ou a busca tem de impedir esta resposta de entrar no cache.
        long epoch = cache.epoch();
        Timer timer = new Timer();
        Progress progress = new Progress();
        original.onClose(progress::close);
        // Conversa desconhecida ou expirada vira conversa nova, com id novo gerado aqui: o cliente nunca escolhe o id.
        Optional<QuestionAnalysis> previous =
                request.conversationId().flatMap(id -> conversations.last(id, request.actor()));
        String conversationId = previous.isPresent() ? request.conversationId().get() : conversations.newConversation();

        Plan firstPlan = plan(request.question(), previous);
        timer.lap("analise");
        // Só a primeira pergunta de uma conversa usa o cache; uma continuação depende do contexto anterior.
        Optional<CacheKey> key =
                request.conversationId().isEmpty() ? Optional.of(cacheKey(request, firstPlan)) : Optional.empty();
        Optional<CachedAnswer> hit = key.flatMap(cache::get);
        if (hit.isPresent()) {
            replay(
                    request,
                    hit.get(),
                    conversationId,
                    timer,
                    progress,
                    original,
                    () -> conversations.append(conversationId, request.actor(), firstPlan.analysis()));
            return;
        }
        Recorder recorder = new Recorder(original);
        Resolved resolved = resolve(request, firstPlan, previous, timer, progress);
        Plan plan = resolved.plan();
        Retrieval found = resolved.found();
        List<ScoredChunk> chunks = found.chunks();
        Route route = resolved.route();
        if (resolved.fetched()) {
            // Os documentos novos invalidaram o cache durante esta pergunta; a resposta já nasce com eles no índice.
            epoch = resolved.cacheEpoch();
        }
        progress.route(route);
        AnswerSink sink = key.isPresent() ? recorder : original;

        Retrieval context = route == Route.NOT_DOCUMENTED ? found : expandFlow(plan, found);
        recordAsked(request.actor(), conversationId, route, context.chunks());
        Answered answered;
        try {
            answered = route == Route.NOT_DOCUMENTED
                    ? notDocumented(plan, conversationId, chunks, timer, sink, unsupported(plan))
                    : withModel(request, plan, conversationId, context, route, timer, sink, progress);
        } catch (RuntimeException e) {
            String outcome = interrupted(e, progress) ? "interrupted" : "failed";
            try {
                recordAnswered(request.actor(), conversationId, outcome, progress);
            } catch (AuditException auditFailure) {
                e.addSuppressed(auditFailure);
            }
            LOG.warn(
                    "Pergunta não concluída: rota={} desfecho={} falha={} tempos={}",
                    progress.route(),
                    outcome,
                    e.getClass().getName(),
                    timer.timings());
            throw e;
        }
        recordAnswered(request.actor(), conversationId, "completed", progress);
        // Na rota NOT_DOCUMENTED o código disse "não encontrei"/"não detalha"; no fluxo, o modelo pode ter dito o
        // mesmo.
        if ((route == Route.NOT_DOCUMENTED || progress.flowNotDetailed())
                && plan.analysis().sources().contains(Source.INTERNAL)) {
            recordGap(request);
        }
        conversations.append(conversationId, request.actor(), plan.analysis());
        if (key.isPresent() && cacheable(answered.metadata())) {
            cache.put(key.get(), new CachedAnswer(recorder.texts(), answered.metadata()), epoch);
        }
        sink.done(answered.metadata());
        LOG.info(
                "Pergunta respondida: rota={} modelo={} escalada={} topScore={} trechos={} descartadas={} tempos={} "
                        + "refs={}",
                answered.metadata().route(),
                answered.metadata().model().map(Enum::name).orElse("nenhum"),
                progress.escalated(),
                String.format(Locale.ROOT, "%.3f", RouteDecider.topScore(chunks)),
                chunks.size(),
                answered.metadata().droppedSentences(),
                answered.metadata().timingsMillis(),
                chunks.stream().map(c -> c.chunk().documentId().logRef()).toList());
    }

    /**
     * Análise, versão inferida, versões faltantes, o filtro de busca resultante e, numa comparação entre versões, as
     * versões que existem no acervo e serão buscadas uma a uma.
     */
    private record Plan(
            QuestionAnalysis analysis,
            Optional<String> inferred,
            List<String> missing,
            SearchFilter filter,
            Optional<String> versionUsed,
            List<String> perVersion) {

        boolean skipSearch() {
            return RouteDecider.publicOnly(analysis) && !missing.isEmpty() && perVersion.isEmpty();
        }

        boolean byVersion() {
            return !perVersion.isEmpty();
        }
    }

    /**
     * Pergunta de fluxo: os trechos da busca viram documentos (ou seções) inteiros mais os documentos ligados por
     * wiki-link. É só contexto de prompt; o {@code topScore} continua sendo o da busca, e as demais rotas não passam
     * por aqui.
     */
    private Retrieval expandFlow(Plan plan, Retrieval found) {
        if (plan.analysis().type() != QuestionType.FLOW || plan.byVersion()) {
            return found;
        }
        return new Retrieval(flowExpander.expand(found.chunks()), found.groups(), found.capNotice(), found.chunks());
    }

    /** Trechos da busca e, quando a busca foi por versão, os mesmos trechos agrupados por versão. */
    private record Retrieval(
            List<ScoredChunk> chunks,
            List<VersionContext> groups,
            Optional<String> capNotice,
            List<ScoredChunk> searched) {

        Retrieval(List<ScoredChunk> chunks, List<VersionContext> groups) {
            this(chunks, groups, Optional.empty(), chunks);
        }

        Retrieval(List<ScoredChunk> chunks, List<VersionContext> groups, Optional<String> capNotice) {
            this(chunks, groups, capNotice, chunks);
        }
    }

    private Plan plan(String question, Optional<QuestionAnalysis> previous) {
        QuestionAnalysis analysis = analyzer.analyze(question, previous);
        boolean comparison = analysis.type() == QuestionType.COMPARISON;
        // Comparação sem versão ("em qual versão foi introduzido?") precisa de todas as versões, não só da última.
        Optional<String> inferred = comparison ? Optional.empty() : inferVersion(analysis);
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
        List<String> perVersion = RouteDecider.perVersion(analysis)
                ? analysis.versions().stream().filter(v -> !missing.contains(v)).toList()
                : List.of();
        if (comparison && versions.size() < 2) {
            // Comparação com uma versão só ("isso existe no java 25?"): a resposta pode estar em qualquer versão.
            versions = List.of();
        }
        boolean singlePublicVersion =
                sources.contains(Source.PUBLIC) && analysis.language().isPresent() && versions.size() == 1;
        return new Plan(
                analysis,
                inferred,
                missing,
                new SearchFilter(sources, analysis.language(), versions),
                singlePublicVersion ? Optional.of(versions.get(0)) : Optional.empty(),
                perVersion);
    }

    /**
     * Busca normal, ou uma busca por grupo numa comparação: um grupo por versão pública e, numa pergunta mista, um para
     * a documentação interna. O teto {@code sage.retrieval.comparison-max-chunks} (8) é dividido entre os grupos, com o
     * resto indo para os primeiros: duas versões ficam com 4 e 4, duas versões e a interna com 3, 3 e 2. Cada grupo só
     * fica com os trechos no limiar de relevância; o que um grupo não usou vai, numa segunda busca, para os grupos que
     * encheram a sua parte. Buscando tudo junto, a versão com mais texto sobre o assunto ocuparia o contexto inteiro e
     * a outra pareceria não ter nada.
     *
     * <p>Para cada grupo buscado ter pelo menos {@link #MIN_CHUNKS_PER_GROUP} trechos, no máximo {@code teto / 2}
     * grupos são buscados (4 com o padrão), contando o interno. Versões além disso não são buscadas, viram grupos
     * marcados como não buscados (sem "não menciona") e o código avisa "Comparei só as versões ...".
     */
    private Retrieval retrieve(String question, Plan plan) {
        if (plan.skipSearch()) {
            return new Retrieval(List.of(), List.of());
        }
        if (!plan.byVersion()) {
            return new Retrieval(retriever.retrieve(question, plan.filter()), List.of());
        }
        boolean internal = plan.analysis().sources().contains(Source.INTERNAL);
        int budget = retriever.comparisonMaxChunks();
        int maxPublic = Math.max(1, budget / MIN_CHUNKS_PER_GROUP - (internal ? 1 : 0));
        List<String> requested = plan.perVersion();
        List<String> searched = requested.subList(0, Math.min(maxPublic, requested.size()));
        List<String> skipped = requested.subList(searched.size(), requested.size());
        List<VersionContext> slots = new ArrayList<>();
        List<SearchFilter> filters = new ArrayList<>();
        for (String version : searched) {
            slots.add(new VersionContext(version, List.of()));
            filters.add(new SearchFilter(Set.of(Source.PUBLIC), plan.analysis().language(), List.of(version)));
        }
        if (internal) {
            slots.add(VersionContext.internal(List.of()));
            filters.add(new SearchFilter(Set.of(Source.INTERNAL), Optional.empty(), List.of()));
        }
        int[] shares = fairSplit(budget, slots.size());
        List<List<ScoredChunk>> found = new ArrayList<>();
        int spare = 0;
        for (int i = 0; i < slots.size(); i++) {
            shares[i] = Math.max(MIN_CHUNKS_PER_GROUP, shares[i]);
            found.add(decider.relevant(retriever.retrieve(question, filters.get(i), shares[i])));
            spare += shares[i] - found.get(i).size();
        }
        List<Integer> full = new ArrayList<>();
        for (int i = 0; i < slots.size(); i++) {
            if (shares[i] > 0 && found.get(i).size() == shares[i]) {
                full.add(i);
            }
        }
        if (spare > 0 && !full.isEmpty()) {
            int[] extra = fairSplit(spare, full.size());
            for (int k = 0; k < full.size(); k++) {
                int i = full.get(k);
                shares[i] += extra[k];
                found.set(i, decider.relevant(retriever.retrieve(question, filters.get(i), shares[i])));
            }
        }
        List<VersionContext> groups = new ArrayList<>(slots.size());
        List<ScoredChunk> all = new ArrayList<>();
        for (int i = 0; i < slots.size(); i++) {
            VersionContext slot = slots.get(i);
            groups.add(new VersionContext(slot.version(), slot.internal(), true, found.get(i)));
            all.addAll(found.get(i));
        }
        skipped.forEach(v -> groups.add(VersionContext.notSearched(v)));
        Optional<String> notice = skipped.isEmpty()
                ? Optional.empty()
                : Optional.of(ONLY_COMPARED.formatted(joined(
                        searched.stream().map(v -> languageName(plan) + " " + v).toList())));
        return new Retrieval(List.copyOf(all), List.copyOf(groups), notice);
    }

    /** "A", "A e B", "A, B e C". */
    private static String joined(List<String> items) {
        if (items.size() <= 1) {
            return String.join("", items);
        }
        return String.join(", ", items.subList(0, items.size() - 1)) + " e " + items.get(items.size() - 1);
    }

    /** Divide {@code total} em {@code parts} partes que diferem em no máximo 1, as maiores primeiro. */
    private static int[] fairSplit(int total, int parts) {
        int[] shares = new int[parts];
        for (int i = 0; i < parts; i++) {
            shares[i] = total / parts + (i < total % parts ? 1 : 0);
        }
        return shares;
    }

    /**
     * Pergunta que envolve a documentação interna e terminou em "não encontrei" (ou "não detalha esse fluxo") vira uma
     * lacuna para os editores. Falhar aqui não derruba a resposta: o usuário já a recebeu, e a lacuna não é a auditoria
     * (que continua fail-closed). O log leva só a classe da falha, nunca a pergunta.
     */
    private void recordGap(AnswerRequest request) {
        try {
            gaps.record(request.question(), request.actor());
        } catch (RuntimeException e) {
            LOG.warn("Lacuna não gravada ({})", e.getClass().getSimpleName());
        }
    }

    /** Plano, trechos e rota depois da busca e, se coube, do fallback web ({@code fetched}: documentos novos). */
    private record Resolved(Plan plan, Retrieval found, Route route, boolean fetched, long cacheEpoch) {}

    /**
     * Busca e decide a rota. Numa pergunta só pública, de linguagem com fontes oficiais e com buscador configurado, em
     * que a versão pedida falta no acervo ou o melhor trecho fica abaixo do limiar (rota WEB_FALLBACK), tenta trazer a
     * documentação oficial; se algo foi gravado, refaz análise, busca e decisão com o índice atualizado. Se a web não
     * trouxe nada, segue como se ela não existisse: as mesmas mensagens fixas de antes ("Não tenho a documentação do
     * Java 8 no acervo."), sem lacuna, porque a pergunta é pública.
     */
    private Resolved resolve(
            AnswerRequest request, Plan plan, Optional<QuestionAnalysis> previous, Timer timer, Progress progress) {
        Retrieval found = retrieve(request.question(), plan);
        timer.lap("busca");
        Route route = decider.decide(plan.analysis(), found.chunks(), plan.missing());
        if (!web.active()
                || !decider.webFallbackNeeded(plan.analysis(), found.chunks(), plan.missing())
                || !web.supports(plan.analysis().language().orElse(""))) {
            return new Resolved(plan, found, route, false, 0L);
        }
        if (progress.clientGone()) {
            throw new AnswerAbortedException(AnswerAbortedException.Reason.CLIENT_GONE);
        }
        progress.route(Route.WEB_FALLBACK);
        List<Document> saved = fetchFromWeb(request, plan);
        // Lida logo depois da gravação (cujo evento já esvaziou o cache) e antes de refazer a busca: uma invalidação
        // que chegue depois disso ainda impede esta resposta de entrar no cache.
        long epochAfterFetch = cache.epoch();
        timer.lap("web");
        if (saved.isEmpty()) {
            return new Resolved(plan, found, route, false, epochAfterFetch);
        }
        Plan updated = plan(request.question(), previous);
        Retrieval again = retrieve(request.question(), updated);
        timer.lap("busca");
        return new Resolved(
                updated,
                again,
                decider.decide(updated.analysis(), again.chunks(), updated.missing()),
                true,
                epochAfterFetch);
    }

    /**
     * Quais versões buscar na web: as pedidas e ausentes do acervo (numa comparação, só as ausentes, dividindo o
     * páginas entre elas) ou, quando a versão existe mas o trecho não serve, a versão em uso. Sem versão certa para
     * rotular o documento não se busca nada: gravar uma página sob a versão errada seria pior que não ter a página.
     */
    private List<Document> fetchFromWeb(AnswerRequest request, Plan plan) {
        int budget = web.maxPages();
        List<String> targets = !plan.missing().isEmpty()
                ? plan.missing()
                : plan.versionUsed().map(List::of).orElse(List.of());
        if (targets.isEmpty() || budget < 1) {
            return List.of();
        }
        targets = targets.subList(0, Math.min(targets.size(), budget));
        int perVersion = Math.max(1, budget / targets.size());
        // Um prazo só para a pergunta inteira: cada versão recebe o que sobrou do anterior, não um prazo novo.
        long deadline = System.nanoTime() + web.timeout().toNanos();
        List<Document> saved = new ArrayList<>();
        for (String version : targets) {
            int left = budget - saved.size();
            Duration remaining = Duration.ofNanos(deadline - System.nanoTime());
            if (left < 1 || remaining.isNegative() || remaining.isZero()) {
                break;
            }
            saved.addAll(web.fetchAndStore(
                    request.question(),
                    plan.analysis(),
                    version,
                    request.actor(),
                    Math.min(perVersion, left),
                    remaining));
        }
        return saved;
    }

    /**
     * Pergunta só pública, de versão ausente do acervo, de linguagem sem fontes oficiais: o código diz isso,
     * sem modelo e sem web. Só vale quando a rota já é NOT_DOCUMENTED (com a versão ou o assunto no acervo, responde-se
     * normalmente).
     */
    private boolean unsupported(Plan plan) {
        return RouteDecider.publicOnly(plan.analysis())
                && plan.analysis().language().isPresent()
                && !plan.missing().isEmpty()
                && !web.supports(plan.analysis().language().get());
    }

    /** Fluxo sobre a documentação interna sem trecho relevante: o código diz que o fluxo não é detalhado. */
    private static boolean flowOnInternal(Plan plan) {
        return plan.analysis().type() == QuestionType.FLOW
                && plan.analysis().sources().contains(Source.INTERNAL);
    }

    private Answered notDocumented(
            Plan plan,
            String conversationId,
            List<ScoredChunk> chunks,
            Timer timer,
            AnswerSink sink,
            boolean unsupportedLanguage) {
        AnswerMetadata metadata = new AnswerMetadata(
                conversationId,
                Route.NOT_DOCUMENTED,
                Optional.empty(),
                RouteDecider.topScore(chunks),
                List.of(),
                plan.versionUsed(),
                false,
                0,
                timer.timings(),
                List.of());
        sink.metadata(metadata);
        // Os avisos de versão ausente vêm sempre antes; só então o aviso de que não há fontes oficiais.
        missingNotices(plan).forEach(sink::text);
        if (unsupportedLanguage) {
            sink.text(NO_OFFICIAL_SOURCES);
        } else {
            if (!plan.skipSearch()) {
                plan.inferred().ifPresent(v -> sink.text(INFERRED_VERSION.formatted(v)));
                sink.text(flowOnInternal(plan) ? PromptBuilder.FLOW_NOT_DETAILED : NO_CONTEXT);
            }
        }
        timer.total();
        return new Answered(withFinal(metadata, 0, timer, List.of()));
    }

    /**
     * Chama o modelo. A comparação vai direto para o STRONG; o resto começa no FAST com a ferramenta {@code escalate}
     * liberada. Se o FAST pedir escalada antes de alguma frase aprovada, o stream dele é cancelado e o STRONG responde
     * com o mesmo contexto; pedido depois de frase aprovada é ignorado (ela já está na tela do usuário). Um preâmbulo
     * sem citação ("Vou passar para um modelo mais forte.") não conta: o guard o descarta e a escalada vale. Por isso o
     * {@code metadata} do caminho FAST só sai com a primeira saída aprovada (ou a escalada, ou o fim): ele já diz a
     * rota e o modelo de verdade. Se o modelo falhar antes disso, o {@code metadata} sai mesmo assim, antes do
     * {@code error}. O prazo {@code sage.models.timeout} vale para as duas chamadas somadas.
     */
    private Answered withModel(
            AnswerRequest request,
            Plan plan,
            String conversationId,
            Retrieval found,
            Route route,
            Timer timer,
            AnswerSink sink,
            Progress progress) {
        List<ScoredChunk> chunks = found.chunks();
        List<Citation> citations = citations(chunks);
        ModelTier tier = route == Route.STRONG_MODEL ? ModelTier.STRONG : ModelTier.FAST;
        AnswerMetadata metadata = new AnswerMetadata(
                conversationId,
                route,
                Optional.of(tier),
                RouteDecider.topScore(found.searched()),
                citations,
                plan.versionUsed(),
                chunks.stream().anyMatch(c -> !c.chunk().reviewed()),
                0,
                timer.timings(),
                List.of());
        Opening opening = new Opening(sink, metadata, prelude(plan, found));
        Emitter emitter = new Emitter(sink, opening, chunks, found.groups(), languageName(plan));
        try {
            if (tier == ModelTier.STRONG) {
                opening.send();
            }
            QuestionType type = plan.analysis().type();
            Prompt prompt = plan.byVersion()
                    ? promptBuilder.buildByVersion(request.question(), languageName(plan), found.groups())
                    : promptBuilder.build(request.question(), chunks, type, tier == ModelTier.FAST);
            try (Deadline deadline = new Deadline(models.timeout())) {
                Call call = new Call(
                        chunks,
                        citations,
                        emitter,
                        progress,
                        deadline,
                        type == QuestionType.COMPARISON,
                        type == QuestionType.FLOW);
                if (call.run(tier, prompt)) {
                    progress.escalate();
                    opening.escalate();
                    opening.send();
                    call.run(ModelTier.STRONG, promptBuilder.build(request.question(), chunks, type, false));
                }
            }
        } catch (RuntimeException e) {
            if (!progress.clientGone()) {
                // O cliente ainda está lá: recebe ao menos o metadata (com o conversaId) antes do error.
                try {
                    opening.send();
                } catch (RuntimeException sendFailure) {
                    e.addSuppressed(sendFailure);
                }
            }
            throw e;
        }
        timer.lap("modelo");
        timer.total();
        return new Answered(withFinal(opening.metadata(), progress.dropped(), timer, emitter.consulted()));
    }

    /** Uma chamada ao modelo, com guard novo; devolve {@code true} se o modelo pediu escalada a tempo. */
    private final class Call {
        private final List<ScoredChunk> chunks;
        private final List<Citation> citations;
        private final Emitter emitter;
        private final Progress progress;
        private final Deadline deadline;
        private final boolean comparison;
        private final boolean flow;

        Call(
                List<ScoredChunk> chunks,
                List<Citation> citations,
                Emitter emitter,
                Progress progress,
                Deadline deadline,
                boolean comparison,
                boolean flow) {
            this.flow = flow;
            this.chunks = chunks;
            this.citations = citations;
            this.emitter = emitter;
            this.progress = progress;
            this.deadline = deadline;
            this.comparison = comparison;
        }

        boolean run(ModelTier tier, Prompt prompt) {
            AnswerGuard guard = new AnswerGuard(chunks.size(), comparison, flow);
            progress.start(guard, citations);
            if (progress.clientGone()) {
                throw new AnswerAbortedException(AnswerAbortedException.Reason.CLIENT_GONE);
            }
            AtomicBoolean escalated = new AtomicBoolean();
            languageModel.stream(tier, prompt)
                    .takeUntilOther(progress.closed())
                    .takeUntilOther(deadline.reached())
                    .takeUntil(event -> prompt.allowEscalation()
                            && event instanceof ModelEvent.EscalationRequested
                            && !emitter.started())
                    .doOnNext(event -> handle(event, tier, prompt, guard, escalated))
                    .blockLast();
            if (progress.clientGone()) {
                throw new AnswerAbortedException(AnswerAbortedException.Reason.CLIENT_GONE);
            }
            if (deadline.hit()) {
                throw new AnswerAbortedException(AnswerAbortedException.Reason.MODEL_DEADLINE);
            }
            if (escalated.get()) {
                return true;
            }
            guard.finish().forEach(emitter::accepted);
            emitter.finish();
            return false;
        }

        private void handle(
                ModelEvent event, ModelTier tier, Prompt prompt, AnswerGuard guard, AtomicBoolean escalated) {
            switch (event) {
                case ModelEvent.TextDelta delta -> guard.accept(delta.text()).forEach(emitter::accepted);
                case ModelEvent.EscalationRequested ignored -> {
                    if (prompt.allowEscalation() && !emitter.started()) {
                        escalated.set(true);
                    } else {
                        // Sem o motivo: ele é texto do modelo.
                        LOG.warn(
                                "Pedido de escalada ignorado: modelo={} já tinha frase aprovada ou não podia escalar",
                                tier);
                    }
                }
                case ModelEvent.Completed ignored -> {
                    // Nada a fazer: o finish() do guard roda depois do stream.
                }
            }
        }
    }

    /** Avisos fixos que vêm logo depois do {@code metadata}: versões fora do acervo e versão inferida. */
    private List<String> prelude(Plan plan, Retrieval found) {
        List<String> lines = new ArrayList<>(missingNotices(plan));
        found.capNotice().ifPresent(lines::add);
        plan.inferred().ifPresent(v -> lines.add(INFERRED_VERSION.formatted(v)));
        return lines;
    }

    private String languageName(Plan plan) {
        return plan.analysis().language().map(catalog::displayName).orElse("");
    }

    /**
     * O {@code metadata} e os avisos fixos, enviados uma vez só. No caminho FAST ficam guardados até a primeira saída
     * aprovada, porque uma escalada antes disso troca a rota e o modelo.
     */
    private static final class Opening {
        private final AnswerSink sink;
        private final List<String> lines;
        private AnswerMetadata metadata;
        private boolean sent;

        Opening(AnswerSink sink, AnswerMetadata metadata, List<String> lines) {
            this.sink = sink;
            this.metadata = metadata;
            this.lines = List.copyOf(lines);
        }

        void send() {
            if (!sent) {
                sent = true;
                sink.metadata(metadata);
                lines.forEach(sink::text);
            }
        }

        void escalate() {
            AnswerMetadata m = metadata;
            metadata = new AnswerMetadata(
                    m.conversationId(),
                    Route.STRONG_MODEL,
                    Optional.of(ModelTier.STRONG),
                    m.topScore(),
                    m.citations(),
                    m.versionUsed(),
                    m.unreviewedSources(),
                    m.droppedSentences(),
                    m.timingsMillis(),
                    m.consulted());
        }

        AnswerMetadata metadata() {
            return metadata;
        }
    }

    /** Prazo total do modelo, contado uma vez para a resposta inteira (inclusive a escalada). */
    private static final class Deadline implements AutoCloseable {
        private final Sinks.One<Boolean> reached = Sinks.one();
        private final AtomicBoolean hit = new AtomicBoolean();
        private final Disposable timer;

        Deadline(Duration timeout) {
            this.timer = Mono.delay(timeout).subscribe(tick -> {
                hit.set(true);
                reached.tryEmitValue(Boolean.TRUE);
            });
        }

        Mono<Boolean> reached() {
            return reached.asMono();
        }

        boolean hit() {
            return hit.get();
        }

        @Override
        public void close() {
            timer.dispose();
        }
    }

    /**
     * Emite as saídas aprovadas, precedidas do {@code metadata} (na primeira) e da linha de versão de cada fonte que
     * elas citam pela primeira vez, e guarda essas fontes para o {@code documentacaoConsultada} do {@code done}.
     *
     * <p>Numa comparação, versão sem trecho ganha do código a linha "A documentação do Java 17 disponível não menciona
     * isso." na posição dela: logo antes da primeira frase que cita um grupo posterior a ela, ou no fim, antes do
     * {@code done}, se nenhum grupo posterior for citado. Assim a resposta continua na ordem das versões.
     */
    private final class Emitter {
        private final AnswerSink sink;
        private final Opening opening;
        private final List<ScoredChunk> chunks;
        private final int[] groupOfChunk;
        private final List<Pending> pending = new ArrayList<>();
        private final Set<String> announced = new HashSet<>();
        private final List<ConsultedSource> consulted = new ArrayList<>();
        private boolean started;

        private record Pending(int group, String line) {}

        Emitter(
                AnswerSink sink,
                Opening opening,
                List<ScoredChunk> chunks,
                List<VersionContext> groups,
                String languageName) {
            this.sink = sink;
            this.opening = opening;
            this.chunks = chunks;
            this.groupOfChunk = new int[chunks.size()];
            int next = 0;
            for (int g = 0; g < groups.size(); g++) {
                VersionContext group = groups.get(g);
                for (int k = 0; k < group.chunks().size(); k++) {
                    groupOfChunk[next++] = g;
                }
                if (group.chunks().isEmpty() && !group.internal() && group.searched()) {
                    pending.add(new Pending(g, NOT_MENTIONED.formatted(languageName + " " + group.version())));
                }
            }
        }

        /** Já saiu alguma frase ou bloco aprovado (daí em diante, escalada não vale mais). */
        boolean started() {
            return started;
        }

        void accepted(String text) {
            started = true;
            opening.send();
            for (int n : AnswerGuard.citationsIn(text)) {
                if (n >= 1 && n <= chunks.size()) {
                    flushPendingBefore(groupOfChunk[n - 1]);
                    Chunk chunk = chunks.get(n - 1).chunk();
                    String line = CONSULTED.formatted(label(chunk));
                    if (announced.add(line)) {
                        sink.text(line);
                        consulted.add(consultedSource(chunk));
                    }
                }
            }
            sink.text(text);
        }

        /** Fim da resposta: o que ainda estiver pendente sai agora, antes do {@code done}. */
        void finish() {
            opening.send();
            flushPendingBefore(Integer.MAX_VALUE);
        }

        private void flushPendingBefore(int group) {
            while (!pending.isEmpty() && pending.get(0).group() < group) {
                sink.text(pending.remove(0).line());
            }
        }

        List<ConsultedSource> consulted() {
            return List.copyOf(consulted);
        }
    }

    private String label(Chunk chunk) {
        if (chunk.source() == Source.INTERNAL) {
            return chunk.language() + " (interna)";
        }
        return catalog.displayName(chunk.language()) + " " + chunk.version().orElse(NO_VERSION);
    }

    private ConsultedSource consultedSource(Chunk chunk) {
        if (chunk.source() == Source.INTERNAL) {
            return new ConsultedSource(chunk.language(), chunk.language(), Optional.empty(), Source.INTERNAL);
        }
        return new ConsultedSource(
                chunk.language(), catalog.displayName(chunk.language()), chunk.version(), Source.PUBLIC);
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
        recordAskedDocs(
                actor,
                conversationId,
                route,
                chunks.stream()
                        .map(c -> c.chunk().documentId().value())
                        .distinct()
                        .toList());
    }

    private void recordAskedDocs(String actor, String conversationId, Route route, List<String> contextDocIds) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("conversationId", conversationId);
        details.put("outcome", "started");
        details.put("route", route.name());
        details.put("model", tierOf(route).map(Enum::name).orElse("none"));
        details.put("contextDocIds", String.join(",", contextDocIds));
        audit.record(new AuditEvent(AuditEventType.QUESTION_ASKED, actor, clock.instant(), details));
    }

    /** Grava a rota e o modelo finais: depois de uma escalada, STRONG_MODEL e STRONG, com {@code escalated=true}. */
    private void recordAnswered(String actor, String conversationId, String outcome, Progress progress) {
        recordAnswered(
                actor,
                conversationId,
                outcome,
                progress.route(),
                progress.escalated(),
                progress.citedDocIds(),
                progress.dropped());
    }

    private void recordAnswered(
            String actor,
            String conversationId,
            String outcome,
            Route route,
            boolean escalated,
            List<String> citedDocIds,
            int dropped) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("conversationId", conversationId);
        details.put("outcome", outcome);
        details.put("route", route.name());
        details.put("model", tierOf(route).map(Enum::name).orElse("none"));
        details.put("escalated", Boolean.toString(escalated));
        details.put("citedDocIds", String.join(",", citedDocIds));
        details.put("droppedSentences", Integer.toString(dropped));
        audit.record(new AuditEvent(AuditEventType.QUESTION_ANSWERED, actor, clock.instant(), details));
    }

    private CacheKey cacheKey(AnswerRequest request, Plan plan) {
        QuestionAnalysis analysis = plan.analysis();
        List<String> versions = plan.inferred().map(List::of).orElse(analysis.versions());
        return new CacheKey(
                AnswerCache.normalize(request.question()),
                analysis.sources(),
                analysis.language(),
                versions,
                analysis.type());
    }

    /**
     * Só vai para o cache resposta concluída, com texto do modelo e sem nenhuma frase barrada pelo guard: repetir por
     * horas uma resposta que o guard teve de podar tira do modelo a chance de acertar numa segunda tentativa.
     * {@code NOT_DOCUMENTED} é barato de refazer e muda assim que o acervo muda.
     */
    private static boolean cacheable(AnswerMetadata metadata) {
        return metadata.route() != Route.NOT_DOCUMENTED && metadata.droppedSentences() == 0;
    }

    /**
     * Reemite uma resposta do cache: o {@code metadata} (sem as fontes consultadas, como na resposta ao vivo), os
     * mesmos eventos {@code text} na mesma ordem e o {@code done} com {@code documentacaoConsultada}, com rota
     * {@code CACHE}, tempos novos e o {@code conversaId} desta conversa. A auditoria vale como numa resposta normal e
     * continua fail-closed: se {@code QUESTION_ASKED} falhar, nada é reemitido.
     */
    private void replay(
            AnswerRequest request,
            CachedAnswer cached,
            String conversationId,
            Timer timer,
            Progress progress,
            AnswerSink sink,
            Runnable remember) {
        AnswerMetadata stored = cached.metadata();
        progress.route(Route.CACHE);
        recordAskedDocs(
                request.actor(),
                conversationId,
                Route.CACHE,
                stored.citations().stream()
                        .map(c -> c.documentId().value())
                        .distinct()
                        .toList());
        List<String> cited = citedDocIds(cached);
        try {
            sink.metadata(replayed(stored, conversationId, timer.timings(), List.of()));
            cached.sentences().forEach(sink::text);
        } catch (RuntimeException e) {
            try {
                recordAnswered(request.actor(), conversationId, "interrupted", Route.CACHE, false, cited, 0);
            } catch (AuditException auditFailure) {
                e.addSuppressed(auditFailure);
            }
            throw e;
        }
        timer.total();
        AnswerMetadata done = replayed(stored, conversationId, timer.timings(), stored.consulted());
        recordAnswered(request.actor(), conversationId, "completed", Route.CACHE, false, cited, 0);
        remember.run();
        sink.done(done);
        LOG.info(
                "Pergunta respondida: rota=CACHE modelo=nenhum trechos={} refs={} tempos={}",
                stored.citations().size(),
                stored.citations().stream().map(c -> c.documentId().logRef()).toList(),
                done.timingsMillis());
    }

    private static List<String> citedDocIds(CachedAnswer cached) {
        SortedSet<Integer> numbers = new TreeSet<>();
        cached.sentences().forEach(s -> AnswerGuard.citationsIn(s).forEach(numbers::add));
        List<Citation> citations = cached.metadata().citations();
        return numbers.stream()
                .filter(n -> n >= 1 && n <= citations.size())
                .map(n -> citations.get(n - 1).documentId().value())
                .distinct()
                .toList();
    }

    private static AnswerMetadata replayed(
            AnswerMetadata stored, String conversationId, Map<String, Long> timings, List<ConsultedSource> consulted) {
        return new AnswerMetadata(
                conversationId,
                Route.CACHE,
                Optional.empty(),
                stored.topScore(),
                stored.citations(),
                stored.versionUsed(),
                stored.unreviewedSources(),
                stored.droppedSentences(),
                timings,
                consulted);
    }

    /** Repassa os eventos ao destino real e guarda os {@code text} na ordem, para a resposta poder ir ao cache. */
    private static final class Recorder implements AnswerSink {
        private final AnswerSink target;
        private final List<String> texts = new ArrayList<>();

        Recorder(AnswerSink target) {
            this.target = target;
        }

        List<String> texts() {
            return List.copyOf(texts);
        }

        @Override
        public void metadata(AnswerMetadata m) {
            target.metadata(m);
        }

        @Override
        public void text(String sentence) {
            texts.add(sentence);
            target.text(sentence);
        }

        @Override
        public void done(AnswerMetadata finalMetadata) {
            target.done(finalMetadata);
        }

        @Override
        public void onClose(Runnable callback) {
            target.onClose(callback);
        }
    }

    private static Optional<ModelTier> tierOf(Route route) {
        return switch (route) {
            case FAST_MODEL -> Optional.of(ModelTier.FAST);
            case STRONG_MODEL -> Optional.of(ModelTier.STRONG);
            default -> Optional.empty();
        };
    }

    private static AnswerMetadata withFinal(
            AnswerMetadata m, int dropped, Timer timer, List<ConsultedSource> consulted) {
        return new AnswerMetadata(
                m.conversationId(),
                m.route(),
                m.model(),
                m.topScore(),
                m.citations(),
                m.versionUsed(),
                m.unreviewedSources(),
                dropped,
                timer.timings(),
                consulted);
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
        private volatile Route route = Route.NOT_DOCUMENTED;
        private volatile boolean escalated;
        private final AtomicInteger droppedBefore = new AtomicInteger();

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

        void route(Route decided) {
            this.route = decided;
        }

        Route route() {
            return route;
        }

        void escalate() {
            this.escalated = true;
            this.route = Route.STRONG_MODEL;
        }

        boolean escalated() {
            return escalated;
        }

        /** Troca o guard (a escalada começa um novo); o que o anterior descartou continua na conta. */
        void start(AnswerGuard started, List<Citation> context) {
            AnswerGuard previous = guard;
            if (previous != null) {
                droppedBefore.addAndGet(previous.dropped());
            }
            this.citations = context;
            this.guard = started;
        }

        boolean flowNotDetailed() {
            AnswerGuard current = guard;
            return current != null && current.flowNotDetailed();
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
            return droppedBefore.get() + (current == null ? 0 : current.dropped());
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
