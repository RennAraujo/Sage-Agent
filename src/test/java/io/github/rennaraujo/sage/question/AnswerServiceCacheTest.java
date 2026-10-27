package io.github.rennaraujo.sage.question;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.rennaraujo.sage.audit.AuditEvent;
import io.github.rennaraujo.sage.audit.AuditEventType;
import io.github.rennaraujo.sage.audit.AuditException;
import io.github.rennaraujo.sage.audit.AuditLog;
import io.github.rennaraujo.sage.document.Document;
import io.github.rennaraujo.sage.document.DocumentDeletedEvent;
import io.github.rennaraujo.sage.document.DocumentId;
import io.github.rennaraujo.sage.document.DocumentSavedEvent;
import io.github.rennaraujo.sage.document.Source;
import io.github.rennaraujo.sage.question.AnswerServiceTest.RecordingSink;
import io.github.rennaraujo.sage.question.llm.FakeLanguageModel;
import io.github.rennaraujo.sage.question.llm.LanguageModel;
import io.github.rennaraujo.sage.question.llm.ModelProperties;
import io.github.rennaraujo.sage.question.llm.PromptBuilder;
import io.github.rennaraujo.sage.search.Chunk;
import io.github.rennaraujo.sage.search.RetrievalProperties;
import io.github.rennaraujo.sage.search.Retriever;
import io.github.rennaraujo.sage.search.ScoredChunk;
import io.github.rennaraujo.sage.search.SearchFilter;
import io.github.rennaraujo.sage.search.SearchIndex;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

class AnswerServiceCacheTest {

    private static final String ACTOR = "leitor";
    private static final String QUESTION = "como usar toList no java 21?";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-27T12:00:00Z"), ZoneOffset.UTC);
    private static final ModelProperties MODELS =
            new ModelProperties("haiku-id", "sonnet-id", 1500, 0.0, Duration.ofSeconds(30));
    private static final RetrievalProperties RETRIEVAL = new RetrievalProperties(1500, 20, 5, 0.35, 0.0, 8);

    private final SearchIndex index = mock(SearchIndex.class);
    private final Retriever retriever = mock(Retriever.class);
    private final AuditLog audit = mock(AuditLog.class);
    private final AliasCatalog catalog = new AliasCatalog(new FakeCatalogs.Store());
    private final QuestionAnalyzer analyzer = new QuestionAnalyzer(catalog);
    private final AtomicLong nanos = new AtomicLong();
    private final AnswerCache cache = new AnswerCache(new CacheProperties(Duration.ofHours(6), 1000), nanos::get);
    private final ConversationStore conversations =
            new ConversationStore(new ConversationProperties(Duration.ofMinutes(30), 5, 10_000), nanos::get);
    private final FakeLanguageModel model = new FakeLanguageModel("Use toList() [1].");

    @BeforeEach
    void setUp() {
        when(index.versionsOf("java")).thenReturn(new TreeSet<>(List.of("17", "21", "25")));
        when(retriever.retrieve(anyString(), any())).thenReturn(List.of(chunk("publico/java/21/streams.md", 0.92)));
    }

    @Test
    void identicalQuestionServedFromCache() {
        RecordingSink first = new RecordingSink();
        RecordingSink second = new RecordingSink();

        ask(QUESTION, Optional.empty(), first);
        ask("  Como usar TOLIST no Java 21 ", Optional.empty(), second);

        assertThat(model.tiers()).hasSize(1);
        verify(retriever, times(1)).retrieve(anyString(), any());
        assertThat(first.events)
                .containsExactly("metadata", "text:Documentação consultada: Java 21", "text:Use toList() [1].", "done");
        assertThat(second.events).isEqualTo(first.events);
        AnswerMetadata live = first.metadata.get(1);
        AnswerMetadata replayed = second.metadata.get(0);
        assertThat(replayed.route()).isEqualTo(Route.CACHE);
        assertThat(replayed.model()).isEmpty();
        assertThat(replayed.conversationId()).isNotBlank().isNotEqualTo(live.conversationId());
        assertThat(replayed.citations()).isEqualTo(live.citations());
        assertThat(replayed.versionUsed()).isEqualTo(live.versionUsed());
        assertThat(replayed.consulted()).isEmpty();
        assertThat(replayed.timingsMillis()).containsKey("analise");
        AnswerMetadata done = second.metadata.get(1);
        assertThat(done.route()).isEqualTo(Route.CACHE);
        assertThat(done.timingsMillis()).containsKeys("analise", "total");
        assertThat(done.consulted()).isEqualTo(live.consulted()).hasSize(1);
        assertThat(done.consulted().get(0).version()).contains("21");
        assertThat(done.conversationId()).isEqualTo(replayed.conversationId());
    }

    @Test
    void cacheHitIsAuditedAndAuditStaysFailClosed() {
        ask(QUESTION, Optional.empty(), new RecordingSink());
        RecordingSink hit = new RecordingSink();
        ask(QUESTION, Optional.empty(), hit);

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit, times(4)).record(captor.capture());
        AuditEvent asked = captor.getAllValues().get(2);
        AuditEvent answered = captor.getAllValues().get(3);
        assertThat(asked.type()).isEqualTo(AuditEventType.QUESTION_ASKED);
        assertThat(asked.details()).containsEntry("route", "CACHE").containsEntry("outcome", "started");
        assertThat(answered.type()).isEqualTo(AuditEventType.QUESTION_ANSWERED);
        assertThat(answered.details())
                .containsEntry("route", "CACHE")
                .containsEntry("outcome", "completed")
                .containsEntry("citedDocIds", "publico/java/21/streams.md")
                .containsEntry("conversationId", hit.metadata.get(0).conversationId());

        doThrow(new AuditException()).when(audit).record(any());
        RecordingSink blocked = new RecordingSink();
        assertThatThrownBy(() -> ask(QUESTION, Optional.empty(), blocked)).isInstanceOf(AuditException.class);
        assertThat(blocked.events).isEmpty();
    }

    @Test
    void cacheClearedOnDocumentSaved() {
        ask(QUESTION, Optional.empty(), new RecordingSink());
        cache.onSaved(new DocumentSavedEvent(mock(Document.class)));
        ask(QUESTION, Optional.empty(), new RecordingSink());

        assertThat(model.tiers()).hasSize(2);
    }

    @Test
    void cacheClearedOnDocumentDeleted() {
        ask(QUESTION, Optional.empty(), new RecordingSink());
        cache.onDeleted(new DocumentDeletedEvent(DocumentId.of("publico/java/21/streams.md")));
        ask(QUESTION, Optional.empty(), new RecordingSink());

        assertThat(model.tiers()).hasSize(2);
    }

    @Test
    void followUpNotCached() {
        RecordingSink first = new RecordingSink();
        ask(QUESTION, Optional.empty(), first);
        String id = first.metadata.get(0).conversationId();

        ask(QUESTION, Optional.of(id), new RecordingSink());
        ask(QUESTION, Optional.of(id), new RecordingSink());

        assertThat(model.tiers()).hasSize(3);
    }

    @Test
    void followUpInheritsLanguageAndVersion() {
        RecordingSink first = new RecordingSink();
        ask(QUESTION, Optional.empty(), first);
        String id = first.metadata.get(0).conversationId();

        RecordingSink second = new RecordingSink();
        ask("e o collect?", Optional.of(id), second);

        ArgumentCaptor<SearchFilter> filter = ArgumentCaptor.forClass(SearchFilter.class);
        verify(retriever, times(2)).retrieve(anyString(), filter.capture());
        SearchFilter followUp = filter.getAllValues().get(1);
        assertThat(followUp.language()).contains("java");
        assertThat(followUp.versions()).containsExactly("21");
        assertThat(second.metadata.get(0).conversationId()).isEqualTo(id);
    }

    @Test
    void firstQuestionServedFromCacheStillStartsAConversation() {
        ask(QUESTION, Optional.empty(), new RecordingSink());
        RecordingSink hit = new RecordingSink();
        ask(QUESTION, Optional.empty(), hit);

        ask("e o collect?", Optional.of(hit.metadata.get(0).conversationId()), new RecordingSink());

        ArgumentCaptor<SearchFilter> filter = ArgumentCaptor.forClass(SearchFilter.class);
        verify(retriever, times(2)).retrieve(anyString(), filter.capture());
        assertThat(filter.getAllValues().get(1).language()).contains("java");
    }

    @Test
    void conversationExpiresAfterTtl() {
        RecordingSink first = new RecordingSink();
        ask(QUESTION, Optional.empty(), first);
        String id = first.metadata.get(0).conversationId();
        nanos.addAndGet(Duration.ofMinutes(31).toNanos());

        RecordingSink second = new RecordingSink();
        ask("e o collect?", Optional.of(id), second);

        ArgumentCaptor<SearchFilter> filter = ArgumentCaptor.forClass(SearchFilter.class);
        verify(retriever, times(2)).retrieve(anyString(), filter.capture());
        assertThat(filter.getAllValues().get(1).language()).isEmpty();
        assertThat(second.metadata.get(0).conversationId()).isNotEqualTo(id);
    }

    @Test
    void unknownConversationIdIsTreatedAsNewConversation() {
        RecordingSink sink = new RecordingSink();
        ask(QUESTION, Optional.of("nunca-existiu"), sink);

        assertThat(sink.events).contains("done");
        assertThat(sink.metadata.get(0).conversationId()).isNotBlank().isNotEqualTo("nunca-existiu");
        verify(retriever, times(1)).retrieve(anyString(), any());
    }

    @Test
    void notDocumentedIsNotCached() {
        when(retriever.retrieve(anyString(), any())).thenReturn(List.of());
        ask(QUESTION, Optional.empty(), new RecordingSink());
        when(retriever.retrieve(anyString(), any())).thenReturn(List.of(chunk("publico/java/21/streams.md", 0.92)));

        RecordingSink second = new RecordingSink();
        ask(QUESTION, Optional.empty(), second);

        assertThat(second.metadata.get(0).route()).isEqualTo(Route.FAST_MODEL);
        assertThat(model.tiers()).hasSize(1);
    }

    @Test
    void answerWithDroppedSentenceIsNotCached() {
        FakeLanguageModel sloppy = new FakeLanguageModel("Use toList() [1]. Isto não cita nada.");
        RecordingSink first = new RecordingSink();
        service(sloppy).answer(new AnswerRequest(QUESTION, Optional.empty(), ACTOR), first);
        RecordingSink second = new RecordingSink();
        service(sloppy).answer(new AnswerRequest(QUESTION, Optional.empty(), ACTOR), second);

        assertThat(first.metadata.get(1).droppedSentences()).isEqualTo(1);
        assertThat(sloppy.tiers()).hasSize(2);
        assertThat(second.metadata.get(0).route()).isNotEqualTo(Route.CACHE);
    }

    @Test
    void failedAnswerIsNotCached() {
        LanguageModel broken = (tier, prompt) -> Flux.error(new IllegalStateException("bedrock caiu"));
        assertThatThrownBy(() -> service(broken)
                        .answer(new AnswerRequest(QUESTION, Optional.empty(), ACTOR), new RecordingSink()))
                .isInstanceOf(IllegalStateException.class);

        RecordingSink second = new RecordingSink();
        ask(QUESTION, Optional.empty(), second);

        assertThat(second.metadata.get(0).route()).isEqualTo(Route.FAST_MODEL);
    }

    @Test
    void answerWhoseRetrievalStartedBeforeAnEventIsNotCached() {
        LanguageModel racing = (tier, prompt) -> {
            cache.onSaved(new DocumentSavedEvent(mock(Document.class)));
            return Flux.just(new io.github.rennaraujo.sage.question.llm.ModelEvent.TextDelta("Use toList() [1]."));
        };
        service(racing).answer(new AnswerRequest(QUESTION, Optional.empty(), ACTOR), new RecordingSink());

        RecordingSink second = new RecordingSink();
        ask(QUESTION, Optional.empty(), second);

        assertThat(second.metadata.get(0).route()).isEqualTo(Route.FAST_MODEL);
        assertThat(model.tiers()).hasSize(1);
    }

    @Test
    void eventBetweenPlanAndPutRejectsThePut() {
        QuestionAnalyzer racing = org.mockito.Mockito.spy(analyzer);
        java.util.concurrent.atomic.AtomicBoolean fired = new java.util.concurrent.atomic.AtomicBoolean();
        org.mockito.Mockito.doAnswer(call -> {
                    if (fired.compareAndSet(false, true)) {
                        cache.clear();
                    }
                    return call.callRealMethod();
                })
                .when(racing)
                .analyze(anyString(), any());
        service(racing, model).answer(new AnswerRequest(QUESTION, Optional.empty(), ACTOR), new RecordingSink());

        ask(QUESTION, Optional.empty(), new RecordingSink());

        assertThat(model.tiers()).hasSize(2);
    }

    @Test
    void conversationBelongsToTheActorThatStartedIt() {
        RecordingSink first = new RecordingSink();
        ask(QUESTION, Optional.empty(), first);
        String id = first.metadata.get(0).conversationId();

        RecordingSink other = new RecordingSink();
        service(model).answer(new AnswerRequest("e o collect?", Optional.of(id), "outra-pessoa"), other);

        ArgumentCaptor<SearchFilter> filter = ArgumentCaptor.forClass(SearchFilter.class);
        verify(retriever, times(2)).retrieve(anyString(), filter.capture());
        assertThat(filter.getAllValues().get(1).language()).isEmpty();
        assertThat(other.metadata.get(0).conversationId()).isNotEqualTo(id);
    }

    @Test
    void conversationTurnIsRecordedOnlyAfterTheAnswerCompletes() {
        doThrow(new AuditException()).when(audit).record(any());
        assertThatThrownBy(() -> ask(QUESTION, Optional.empty(), new RecordingSink()))
                .isInstanceOf(AuditException.class);
        conversations.cleanUp();

        assertThat(conversations.size()).isZero();
    }

    @Test
    void replayOfInferredVersionAnswerIsIdenticalToTheLiveOne() {
        when(retriever.retrieve(anyString(), any())).thenReturn(List.of(chunk("publico/java/25/streams.md", 0.92)));
        RecordingSink live = new RecordingSink();
        RecordingSink replay = new RecordingSink();

        ask("como usar toList no java?", Optional.empty(), live);
        ask("como usar toList no java?", Optional.empty(), replay);

        assertThat(live.events)
                .containsExactly(
                        "metadata",
                        "text:Considerei a versão 25, a mais recente disponível.",
                        "text:Documentação consultada: Java 25",
                        "text:Use toList() [1].",
                        "done");
        assertThat(replay.events).isEqualTo(live.events);
        assertThat(replay.metadata.get(1).consulted())
                .isEqualTo(live.metadata.get(1).consulted());
        assertThat(model.tiers()).hasSize(1);
    }

    @Test
    void replayOfComparisonWithMissingVersionIsIdenticalToTheLiveOne() {
        when(retriever.comparisonMaxChunks()).thenReturn(8);
        when(retriever.retrieve(anyString(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(chunk("publico/java/25/records.md", 0.9)));
        FakeLanguageModel strong = new FakeLanguageModel("No Java 25 existe [1].");
        RecordingSink live = new RecordingSink();
        RecordingSink replay = new RecordingSink();
        String question = "isso existe no java 25 ou é exclusivo do java 8?";

        service(strong).answer(new AnswerRequest(question, Optional.empty(), ACTOR), live);
        service(strong).answer(new AnswerRequest(question, Optional.empty(), ACTOR), replay);

        assertThat(live.events)
                .containsExactly(
                        "metadata",
                        "text:Não tenho a documentação do Java 8 no acervo.",
                        "text:Documentação consultada: Java 25",
                        "text:No Java 25 existe [1].",
                        "done");
        assertThat(replay.events).isEqualTo(live.events);
        assertThat(replay.metadata.get(1).consulted())
                .isEqualTo(live.metadata.get(1).consulted());
        assertThat(strong.tiers()).hasSize(1);
    }

    @Test
    void replayOfEscalatedAnswerIsIdenticalToTheLiveOne() {
        java.util.List<io.github.rennaraujo.sage.question.llm.ModelTier> tiers = new java.util.ArrayList<>();
        LanguageModel escalating = (tier, prompt) -> {
            tiers.add(tier);
            return tier == io.github.rennaraujo.sage.question.llm.ModelTier.FAST
                    ? Flux.just(new io.github.rennaraujo.sage.question.llm.ModelEvent.EscalationRequested("motivo"))
                    : Flux.just(
                            new io.github.rennaraujo.sage.question.llm.ModelEvent.TextDelta("Use toList() [1]."),
                            new io.github.rennaraujo.sage.question.llm.ModelEvent.Completed());
        };
        RecordingSink live = new RecordingSink();
        RecordingSink replay = new RecordingSink();

        service(escalating).answer(new AnswerRequest(QUESTION, Optional.empty(), ACTOR), live);
        service(escalating).answer(new AnswerRequest(QUESTION, Optional.empty(), ACTOR), replay);

        assertThat(tiers)
                .containsExactly(
                        io.github.rennaraujo.sage.question.llm.ModelTier.FAST,
                        io.github.rennaraujo.sage.question.llm.ModelTier.STRONG);
        assertThat(live.events)
                .containsExactly("metadata", "text:Documentação consultada: Java 21", "text:Use toList() [1].", "done");
        assertThat(live.metadata.get(1).route()).isEqualTo(Route.STRONG_MODEL);
        assertThat(replay.events).isEqualTo(live.events);
        assertThat(replay.metadata.get(1).route()).isEqualTo(Route.CACHE);
        assertThat(replay.metadata.get(1).consulted())
                .isEqualTo(live.metadata.get(1).consulted());
    }

    private void ask(String question, Optional<String> conversationId, RecordingSink sink) {
        service(model).answer(new AnswerRequest(question, conversationId, ACTOR), sink);
    }

    private AnswerService service(LanguageModel languageModel) {
        return service(analyzer, languageModel);
    }

    private AnswerService service(QuestionAnalyzer customAnalyzer, LanguageModel languageModel) {
        return new AnswerService(
                customAnalyzer,
                catalog,
                retriever,
                index,
                new RouteDecider(index, RETRIEVAL, true),
                new PromptBuilder(),
                new FlowContextExpander(index, new FlowProperties(12000, 3, 24000)),
                languageModel,
                audit,
                MODELS,
                cache,
                conversations,
                org.mockito.Mockito.mock(io.github.rennaraujo.sage.gap.GapRegistry.class),
                CLOCK);
    }

    private static ScoredChunk chunk(String docId, double score) {
        return new ScoredChunk(
                new Chunk(
                        "c-" + docId.hashCode(),
                        DocumentId.of(docId),
                        Source.PUBLIC,
                        "java",
                        Optional.of(docId.split("/")[2]),
                        "Streams > toList",
                        "conteúdo",
                        0,
                        true),
                score);
    }
}
