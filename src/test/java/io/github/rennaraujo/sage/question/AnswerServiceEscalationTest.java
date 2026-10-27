package io.github.rennaraujo.sage.question;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.rennaraujo.sage.audit.AuditEvent;
import io.github.rennaraujo.sage.audit.AuditLog;
import io.github.rennaraujo.sage.document.DocumentId;
import io.github.rennaraujo.sage.document.Source;
import io.github.rennaraujo.sage.question.AnswerServiceTest.RecordingSink;
import io.github.rennaraujo.sage.question.llm.LanguageModel;
import io.github.rennaraujo.sage.question.llm.ModelEvent;
import io.github.rennaraujo.sage.question.llm.ModelProperties;
import io.github.rennaraujo.sage.question.llm.ModelTier;
import io.github.rennaraujo.sage.question.llm.Prompt;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

class AnswerServiceEscalationTest {

    private static final String ACTOR = "leitor";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-27T12:00:00Z"), ZoneOffset.UTC);
    private static final ModelProperties MODELS =
            new ModelProperties("haiku-id", "sonnet-id", 1500, 0.0, Duration.ofSeconds(30));
    private static final RetrievalProperties RETRIEVAL = new RetrievalProperties(1500, 20, 5, 0.35, 0.0, 8);
    private static final int CONTEXT_CHUNKS = 5;
    private static final int COMPARISON_MAX = 8;
    private static final String REASON = "motivo-secreto-do-modelo";

    private final SearchIndex index = mock(SearchIndex.class);
    private final Retriever retriever = mock(Retriever.class);
    private final AuditLog audit = mock(AuditLog.class);
    private final AliasCatalog catalog = new AliasCatalog(new FakeCatalogs.Store());
    private final QuestionAnalyzer analyzer = new QuestionAnalyzer(catalog);
    private final RecordingSink sink = new RecordingSink();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger serviceLogger = (Logger) LoggerFactory.getLogger(AnswerService.class);

    @BeforeEach
    void setUp() {
        when(index.versionsOf("java")).thenReturn(new TreeSet<>(List.of("17", "21", "25")));
        when(retriever.contextChunks()).thenReturn(CONTEXT_CHUNKS);
        when(retriever.comparisonMaxChunks()).thenReturn(COMPARISON_MAX);
        logs.start();
        serviceLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        serviceLogger.detachAppender(logs);
    }

    @Test
    @Timeout(10)
    void escalationBeforeTextSwitchesToStrong() {
        when(retriever.retrieve(anyString(), any()))
                .thenReturn(List.of(chunk("publico/java/21/streams.md", "21", 0.9)));
        AtomicInteger fastCancelled = new AtomicInteger();
        TieredModel model = new TieredModel(
                Flux.<ModelEvent>just(new ModelEvent.TextDelta(" "), new ModelEvent.EscalationRequested(REASON))
                        .concatWith(Flux.never())
                        .doOnCancel(fastCancelled::incrementAndGet),
                Flux.just(new ModelEvent.TextDelta("Use toList() [1]."), new ModelEvent.Completed()));

        service(model).answer(new AnswerRequest("toList no java 21", Optional.empty(), ACTOR), sink);

        assertThat(model.tiers).containsExactly(ModelTier.FAST, ModelTier.STRONG);
        assertThat(fastCancelled).hasValue(1);
        assertThat(model.prompts.get(0).allowEscalation()).isTrue();
        assertThat(model.prompts.get(1).allowEscalation()).isFalse();
        assertThat(sink.events)
                .containsExactly("metadata", "text:Documentação consultada: Java 21", "text:Use toList() [1].", "done");
        for (AnswerMetadata meta : sink.metadata) {
            assertThat(meta.route()).isEqualTo(Route.STRONG_MODEL);
            assertThat(meta.model()).contains(ModelTier.STRONG);
        }
        AuditEvent answered = auditEvents().get(1);
        assertThat(answered.details())
                .containsEntry("route", "STRONG_MODEL")
                .containsEntry("model", "STRONG")
                .containsEntry("escalated", "true");
        assertThat(logs.list).noneMatch(e -> e.getFormattedMessage().contains(REASON));
    }

    @Test
    void escalationAfterTextIsIgnored() {
        when(retriever.retrieve(anyString(), any()))
                .thenReturn(List.of(chunk("publico/java/21/streams.md", "21", 0.9)));
        TieredModel model = new TieredModel(
                Flux.just(
                        new ModelEvent.TextDelta("Use toList() [1]. Depois"),
                        new ModelEvent.EscalationRequested(REASON),
                        new ModelEvent.TextDelta(" collect [1]."),
                        new ModelEvent.Completed()),
                Flux.error(new AssertionError("STRONG não deveria ser chamado")));

        service(model).answer(new AnswerRequest("toList no java 21", Optional.empty(), ACTOR), sink);

        assertThat(model.tiers).containsExactly(ModelTier.FAST);
        assertThat(sink.events)
                .containsExactly(
                        "metadata",
                        "text:Documentação consultada: Java 21",
                        "text:Use toList() [1].",
                        "text:Depois collect [1].",
                        "done");
        assertThat(sink.metadata).allSatisfy(meta -> {
            assertThat(meta.route()).isEqualTo(Route.FAST_MODEL);
            assertThat(meta.model()).contains(ModelTier.FAST);
        });
        assertThat(logs.list)
                .filteredOn(e -> e.getLevel() == Level.WARN)
                .singleElement()
                .satisfies(e -> assertThat(e.getFormattedMessage())
                        .contains("escalada")
                        .doesNotContain(REASON)
                        .doesNotContain("toList"));
        assertThat(auditEvents().get(1).details()).containsEntry("escalated", "false");
    }

    @Test
    void comparisonGoesStraightToStrong() {
        when(retriever.retrieve(anyString(), any()))
                .thenReturn(List.of(
                        chunk("publico/java/17/records.md", "17", 0.9),
                        chunk("publico/java/21/records.md", "21", 0.8)));
        TieredModel model = new TieredModel(
                Flux.error(new AssertionError("FAST não deveria ser chamado")),
                Flux.just(new ModelEvent.TextDelta("Records chegaram no 16 [1]."), new ModelEvent.Completed()));

        service(model)
                .answer(
                        new AnswerRequest("em qual versão do java foi introduzido o record?", Optional.empty(), ACTOR),
                        sink);

        assertThat(model.tiers).containsExactly(ModelTier.STRONG);
        assertThat(model.prompts.get(0).allowEscalation()).isFalse();
        assertThat(model.prompts.get(0).user()).contains("versao=\"17\"").contains("versao=\"21\"");
        ArgumentCaptor<SearchFilter> filter = ArgumentCaptor.forClass(SearchFilter.class);
        verify(retriever).retrieve(anyString(), filter.capture());
        assertThat(filter.getValue().language()).contains("java");
        assertThat(filter.getValue().versions()).isEmpty();
        assertThat(sink.events)
                .containsExactly(
                        "metadata",
                        "text:Documentação consultada: Java 17",
                        "text:Records chegaram no 16 [1].",
                        "done");
        assertThat(sink.metadata.get(0).route()).isEqualTo(Route.STRONG_MODEL);
        assertThat(sink.metadata.get(0).model()).contains(ModelTier.STRONG);
        assertThat(auditEvents().get(0).details())
                .containsEntry("route", "STRONG_MODEL")
                .containsEntry("model", "STRONG");
    }

    @Test
    void comparisonRetrievesEachVersionSeparately() {
        stubPerVersion(Map.of(
                "17", List.of(chunk("publico/java/17/a.md", "17", 0.95), chunk("publico/java/17/b.md", "17", 0.9)),
                "21", List.of(chunk("publico/java/21/c.md", "21", 0.4))));
        TieredModel model = new TieredModel(
                Flux.error(new AssertionError("FAST não deveria ser chamado")),
                Flux.just(
                        new ModelEvent.TextDelta("No Java 17 existe [1]. "),
                        new ModelEvent.TextDelta("No Java 21 também [3]."),
                        new ModelEvent.Completed()));

        service(model).answer(new AnswerRequest("diferença entre java 17 e java 21", Optional.empty(), ACTOR), sink);

        ArgumentCaptor<SearchFilter> filters = ArgumentCaptor.forClass(SearchFilter.class);
        ArgumentCaptor<Integer> limits = ArgumentCaptor.forClass(Integer.class);
        verify(retriever, times(2)).retrieve(anyString(), filters.capture(), limits.capture());
        verify(retriever, never()).retrieve(anyString(), any());
        assertThat(filters.getAllValues())
                .extracting(SearchFilter::versions)
                .containsExactly(List.of("17"), List.of("21"));
        assertThat(filters.getAllValues()).allSatisfy(f -> {
            assertThat(f.sources()).containsExactly(Source.PUBLIC);
            assertThat(f.language()).contains("java");
        });
        assertThat(limits.getAllValues()).containsExactly(4, 4);

        assertThat(model.tiers).containsExactly(ModelTier.STRONG);
        Prompt prompt = model.prompts.get(0);
        assertThat(prompt.user())
                .contains("<documento n=\"1\" fonte=\"publico/java/17/a.md\" secao=\"Seção\" versao=\"17\">")
                .contains("<documento n=\"2\" fonte=\"publico/java/17/b.md\" secao=\"Seção\" versao=\"17\">")
                .contains("<documento n=\"3\" fonte=\"publico/java/21/c.md\" secao=\"Seção\" versao=\"21\">");
        assertThat(prompt.system()).contains("Java 17, Java 21").contains("versão a versão");
        assertThat(sink.events)
                .containsExactly(
                        "metadata",
                        "text:Documentação consultada: Java 17",
                        "text:No Java 17 existe [1].",
                        "text:Documentação consultada: Java 21",
                        "text:No Java 21 também [3].",
                        "done");
        AnswerMetadata done = sink.metadata.get(1);
        assertThat(done.route()).isEqualTo(Route.STRONG_MODEL);
        assertThat(done.versionUsed()).isEmpty();
        assertThat(done.citations()).hasSize(3);
        assertThat(done.consulted())
                .extracting(ConsultedSource::version)
                .containsExactly(Optional.of("17"), Optional.of("21"));
    }

    @Test
    void comparisonWithMissingVersionSaysSoAndAnswersTheRest() {
        stubPerVersion(Map.of("25", List.of(chunk("publico/java/25/records.md", "25", 0.9))));
        TieredModel model = new TieredModel(
                Flux.error(new AssertionError("FAST não deveria ser chamado")),
                Flux.just(new ModelEvent.TextDelta("No Java 25 existe [1]."), new ModelEvent.Completed()));

        service(model)
                .answer(
                        new AnswerRequest("isso existe no java 25 ou é exclusivo do java 8?", Optional.empty(), ACTOR),
                        sink);

        assertThat(sink.events)
                .containsExactly(
                        "metadata",
                        "text:Não tenho a documentação do Java 8 no acervo.",
                        "text:Documentação consultada: Java 25",
                        "text:No Java 25 existe [1].",
                        "done");
        ArgumentCaptor<SearchFilter> filters = ArgumentCaptor.forClass(SearchFilter.class);
        ArgumentCaptor<Integer> limits = ArgumentCaptor.forClass(Integer.class);
        verify(retriever).retrieve(anyString(), filters.capture(), limits.capture());
        assertThat(filters.getValue().versions()).containsExactly("25");
        assertThat(limits.getValue()).isEqualTo(COMPARISON_MAX);
        assertThat(sink.metadata.get(1).route()).isEqualTo(Route.STRONG_MODEL);
        assertThat(model.prompts.get(0).system()).contains("Java 25").doesNotContain("Java 8");
    }

    @Test
    void comparisonWithAllVersionsMissingIsNotDocumented() {
        LanguageModel model = mock(LanguageModel.class);

        service(model).answer(new AnswerRequest("diferença entre java 7 e java 8", Optional.empty(), ACTOR), sink);

        verifyNoInteractions(model);
        assertThat(sink.events)
                .containsExactly(
                        "metadata",
                        "text:Não tenho a documentação do Java 7 no acervo.",
                        "text:Não tenho a documentação do Java 8 no acervo.",
                        "done");
        assertThat(sink.metadata.get(0).route()).isEqualTo(Route.NOT_DOCUMENTED);
    }

    @Test
    void versionWithoutChunksIsSaidByCodeAndLeftOutOfPrompt() {
        stubPerVersion(Map.of("21", List.of(chunk("publico/java/21/records.md", "21", 0.9))));
        TieredModel model = new TieredModel(
                Flux.error(new AssertionError("FAST não deveria ser chamado")),
                Flux.just(new ModelEvent.TextDelta("No Java 21 existe [1]."), new ModelEvent.Completed()));

        service(model).answer(new AnswerRequest("diferença entre java 17 e java 21", Optional.empty(), ACTOR), sink);

        assertThat(sink.events)
                .containsExactly(
                        "metadata",
                        "text:A documentação do Java 17 disponível não menciona isso.",
                        "text:Documentação consultada: Java 21",
                        "text:No Java 21 existe [1].",
                        "done");
        assertThat(model.prompts.get(0).user()).doesNotContain("versao=\"17\"");
    }

    @Test
    @Timeout(10)
    void uncitedPreambleThenEscalationStillSwitchesToStrong() {
        when(retriever.retrieve(anyString(), any()))
                .thenReturn(List.of(chunk("publico/java/21/streams.md", "21", 0.9)));
        AtomicInteger fastCancelled = new AtomicInteger();
        TieredModel model = new TieredModel(
                Flux.<ModelEvent>just(
                                new ModelEvent.TextDelta("Vou passar para um modelo mais forte. "),
                                new ModelEvent.EscalationRequested(REASON))
                        .concatWith(Flux.never())
                        .doOnCancel(fastCancelled::incrementAndGet),
                Flux.just(new ModelEvent.TextDelta("Use toList() [1]."), new ModelEvent.Completed()));

        service(model).answer(new AnswerRequest("toList no java 21", Optional.empty(), ACTOR), sink);

        assertThat(model.tiers).containsExactly(ModelTier.FAST, ModelTier.STRONG);
        assertThat(fastCancelled).hasValue(1);
        assertThat(sink.events)
                .containsExactly("metadata", "text:Documentação consultada: Java 21", "text:Use toList() [1].", "done");
        assertThat(sink.metadata).allSatisfy(meta -> {
            assertThat(meta.route()).isEqualTo(Route.STRONG_MODEL);
            assertThat(meta.model()).contains(ModelTier.STRONG);
        });
        assertThat(auditEvents().get(1).details())
                .containsEntry("route", "STRONG_MODEL")
                .containsEntry("model", "STRONG")
                .containsEntry("escalated", "true");
    }

    @Test
    void modelFailureBeforeAnyTextStillSendsMetadata() {
        when(retriever.retrieve(anyString(), any()))
                .thenReturn(List.of(chunk("publico/java/21/streams.md", "21", 0.9)));
        TieredModel model = new TieredModel(
                Flux.error(new IllegalStateException("bedrock caiu")), Flux.error(new AssertionError("STRONG")));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        service(model).answer(new AnswerRequest("toList no java 21", Optional.of("c-9"), ACTOR), sink))
                .isInstanceOf(IllegalStateException.class);

        assertThat(sink.events).containsExactly("metadata");
        assertThat(sink.metadata.get(0).conversationId()).isEqualTo("c-9");
        assertThat(sink.metadata.get(0).route()).isEqualTo(Route.FAST_MODEL);
        assertThat(auditEvents().get(1).details()).containsEntry("outcome", "interrupted");
    }

    @Test
    void emptyLastVersionIsSaidAfterTheModelText() {
        stubPerVersion(Map.of("17", List.of(chunk("publico/java/17/records.md", "17", 0.9))));
        TieredModel model = new TieredModel(
                Flux.error(new AssertionError("FAST não deveria ser chamado")),
                Flux.just(new ModelEvent.TextDelta("No Java 17 existe [1]."), new ModelEvent.Completed()));

        service(model).answer(new AnswerRequest("diferença entre java 17 e java 21", Optional.empty(), ACTOR), sink);

        assertThat(sink.events)
                .containsExactly(
                        "metadata",
                        "text:Documentação consultada: Java 17",
                        "text:No Java 17 existe [1].",
                        "text:A documentação do Java 21 disponível não menciona isso.",
                        "done");
    }

    @Test
    void versionWithOnlyNoiseIsTreatedAsEmpty() {
        stubPerVersion(Map.of(
                "17", List.of(chunk("publico/java/17/ruido.md", "17", 0.1)),
                "21", List.of(chunk("publico/java/21/records.md", "21", 0.9))));
        TieredModel model = new TieredModel(
                Flux.error(new AssertionError("FAST não deveria ser chamado")),
                Flux.just(new ModelEvent.TextDelta("No Java 21 existe [1]."), new ModelEvent.Completed()));

        service(model).answer(new AnswerRequest("diferença entre java 17 e java 21", Optional.empty(), ACTOR), sink);

        assertThat(sink.events)
                .containsExactly(
                        "metadata",
                        "text:A documentação do Java 17 disponível não menciona isso.",
                        "text:Documentação consultada: Java 21",
                        "text:No Java 21 existe [1].",
                        "done");
        assertThat(model.prompts.get(0).user()).doesNotContain("ruido.md");
        assertThat(sink.metadata.get(1).citations()).hasSize(1);
    }

    @Test
    void unusedSlotsGoToVersionsThatFilledTheirShare() {
        List<ScoredChunk> many = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            many.add(chunk("publico/java/21/r" + i + ".md", "21", 0.9 - i * 0.01));
        }
        stubPerVersion(Map.of("17", List.of(chunk("publico/java/17/records.md", "17", 0.9)), "21", many));
        TieredModel model = new TieredModel(
                Flux.error(new AssertionError("FAST não deveria ser chamado")),
                Flux.just(new ModelEvent.TextDelta("No Java 17 existe [1]."), new ModelEvent.Completed()));

        service(model).answer(new AnswerRequest("diferença entre java 17 e java 21", Optional.empty(), ACTOR), sink);

        ArgumentCaptor<SearchFilter> filters = ArgumentCaptor.forClass(SearchFilter.class);
        ArgumentCaptor<Integer> limits = ArgumentCaptor.forClass(Integer.class);
        verify(retriever, times(3)).retrieve(anyString(), filters.capture(), limits.capture());
        assertThat(filters.getAllValues())
                .extracting(SearchFilter::versions)
                .containsExactly(List.of("17"), List.of("21"), List.of("21"));
        assertThat(limits.getAllValues()).containsExactly(4, 4, 7);
        assertThat(sink.metadata.get(1).citations()).hasSize(COMPARISON_MAX);
    }

    @Test
    void mixedComparisonSplitsPublicVersionsAndInternal() {
        AliasCatalog mixedCatalog = new AliasCatalog(new FakeCatalogs.Store(FakeCatalogs.internal("app-x", List.of())));
        Chunk internal = new Chunk(
                "i1",
                DocumentId.of("interno/app-x/faturamento.md"),
                Source.INTERNAL,
                "app-x",
                Optional.empty(),
                "Faturamento",
                "conteúdo",
                0,
                true);
        stubPerVersion(Map.of(
                "17", List.of(chunk("publico/java/17/a.md", "17", 0.9)),
                "21", List.of(chunk("publico/java/21/b.md", "21", 0.8)),
                "interno", List.of(new ScoredChunk(internal, 0.7))));
        TieredModel model = new TieredModel(
                Flux.error(new AssertionError("FAST não deveria ser chamado")),
                Flux.just(
                        new ModelEvent.TextDelta("No 17 era assim [1]. No 21 mudou [2]. A app X usa o 21 [3]."),
                        new ModelEvent.Completed()));

        service(model, mixedCatalog)
                .answer(
                        new AnswerRequest("isso mudou do java 17 para o 21 na nossa app X?", Optional.empty(), ACTOR),
                        sink);

        ArgumentCaptor<SearchFilter> filters = ArgumentCaptor.forClass(SearchFilter.class);
        ArgumentCaptor<Integer> limits = ArgumentCaptor.forClass(Integer.class);
        verify(retriever, times(3)).retrieve(anyString(), filters.capture(), limits.capture());
        verify(retriever, never()).retrieve(anyString(), any());
        assertThat(filters.getAllValues().get(0).versions()).containsExactly("17");
        assertThat(filters.getAllValues().get(0).sources()).containsExactly(Source.PUBLIC);
        assertThat(filters.getAllValues().get(1).versions()).containsExactly("21");
        assertThat(filters.getAllValues().get(2).sources()).containsExactly(Source.INTERNAL);
        assertThat(filters.getAllValues().get(2).versions()).isEmpty();
        assertThat(limits.getAllValues()).containsExactly(3, 3, 2);
        Prompt prompt = model.prompts.get(0);
        assertThat(prompt.user())
                .contains("<documento n=\"1\" fonte=\"publico/java/17/a.md\" secao=\"Seção\" versao=\"17\">")
                .contains("<documento n=\"2\" fonte=\"publico/java/21/b.md\" secao=\"Seção\" versao=\"21\">")
                .contains("<documento n=\"3\" fonte=\"interno/app-x/faturamento.md\" secao=\"Faturamento\" "
                        + "origem=\"interna\">");
        assertThat(sink.events)
                .containsExactly(
                        "metadata",
                        "text:Documentação consultada: Java 17",
                        "text:No 17 era assim [1].",
                        "text:Documentação consultada: Java 21",
                        "text:No 21 mudou [2].",
                        "text:Documentação consultada: app-x (interna)",
                        "text:A app X usa o 21 [3].",
                        "done");
        assertThat(sink.metadata.get(0).route()).isEqualTo(Route.STRONG_MODEL);
    }

    @Test
    void moreVersionsThanTheCapAreNotSearchedAndSaidSo() {
        when(index.versionsOf("java")).thenReturn(new TreeSet<>(List.of("8", "11", "17", "21", "25", "26")));
        stubPerVersion(Map.of(
                "8", List.of(chunk("publico/java/8/a.md", "8", 0.9)),
                "11", List.of(chunk("publico/java/11/b.md", "11", 0.9)),
                "21", List.of(chunk("publico/java/21/c.md", "21", 0.9)),
                "25", List.of(chunk("publico/java/25/d.md", "25", 0.9))));
        TieredModel model = new TieredModel(
                Flux.error(new AssertionError("FAST não deveria ser chamado")),
                Flux.just(new ModelEvent.TextDelta("No 8 [1]. No 11 [2]. No 21 [3]."), new ModelEvent.Completed()));

        service(model)
                .answer(
                        new AnswerRequest("diferença entre java 8, 11, 17, 21, 25 e 26", Optional.empty(), ACTOR),
                        sink);

        ArgumentCaptor<SearchFilter> filters = ArgumentCaptor.forClass(SearchFilter.class);
        ArgumentCaptor<Integer> limits = ArgumentCaptor.forClass(Integer.class);
        verify(retriever, times(4)).retrieve(anyString(), filters.capture(), limits.capture());
        assertThat(filters.getAllValues())
                .extracting(SearchFilter::versions)
                .containsExactly(List.of("8"), List.of("11"), List.of("17"), List.of("21"));
        assertThat(limits.getAllValues()).containsExactly(2, 2, 2, 2);
        assertThat(sink.events)
                .containsExactly(
                        "metadata",
                        "text:Comparei só as versões Java 8, Java 11, Java 17 e Java 21; peça as demais em outra "
                                + "pergunta.",
                        "text:Documentação consultada: Java 8",
                        "text:No 8 [1].",
                        "text:Documentação consultada: Java 11",
                        "text:No 11 [2].",
                        "text:A documentação do Java 17 disponível não menciona isso.",
                        "text:Documentação consultada: Java 21",
                        "text:No 21 [3].",
                        "done");
        assertThat(sink.events).noneMatch(e -> e.contains("Java 25 disponível") || e.contains("Java 26 disponível"));
    }

    @Test
    void mixedComparisonCountsInternalAsAGroupInTheCap() {
        when(index.versionsOf("java")).thenReturn(new TreeSet<>(List.of("8", "11", "17", "21")));
        AliasCatalog mixedCatalog = new AliasCatalog(new FakeCatalogs.Store(FakeCatalogs.internal("app-x", List.of())));
        stubPerVersion(Map.of("8", List.of(chunk("publico/java/8/a.md", "8", 0.9))));
        TieredModel model = new TieredModel(
                Flux.error(new AssertionError("FAST não deveria ser chamado")),
                Flux.just(new ModelEvent.TextDelta("No 8 [1]."), new ModelEvent.Completed()));

        service(model, mixedCatalog)
                .answer(
                        new AnswerRequest("diferença entre java 8, 11, 17 e 21 na app x", Optional.empty(), ACTOR),
                        sink);

        ArgumentCaptor<SearchFilter> filters = ArgumentCaptor.forClass(SearchFilter.class);
        verify(retriever, times(4)).retrieve(anyString(), filters.capture(), anyInt());
        assertThat(filters.getAllValues().get(3).sources()).containsExactly(Source.INTERNAL);
        assertThat(sink.events)
                .contains("text:Comparei só as versões Java 8, Java 11 e Java 17; peça as demais em outra pergunta.")
                .noneMatch(e -> e.contains("Java 21 disponível"));
    }

    @Test
    void droppedSentencesAddUpAcrossEscalation() {
        when(retriever.retrieve(anyString(), any()))
                .thenReturn(List.of(chunk("publico/java/21/streams.md", "21", 0.9)));
        TieredModel model = new TieredModel(
                Flux.<ModelEvent>just(
                                new ModelEvent.TextDelta("Inventei isto. Outra"),
                                new ModelEvent.EscalationRequested(REASON))
                        .concatWith(Flux.never()),
                Flux.just(new ModelEvent.TextDelta("Use toList() [1]. Sem fonte."), new ModelEvent.Completed()));

        service(model).answer(new AnswerRequest("toList no java 21", Optional.empty(), ACTOR), sink);

        assertThat(model.tiers).containsExactly(ModelTier.FAST, ModelTier.STRONG);
        assertThat(sink.metadata.get(1).droppedSentences()).isEqualTo(2);
        assertThat(auditEvents().get(1).details()).containsEntry("droppedSentences", "2");
    }

    @Test
    void absenceGuardRunsOnComparisonsOnly() {
        stubPerVersion(Map.of(
                "17", List.of(chunk("publico/java/17/a.md", "17", 0.9)),
                "21", List.of(chunk("publico/java/21/b.md", "21", 0.9))));
        TieredModel model = new TieredModel(
                Flux.error(new AssertionError("FAST não deveria ser chamado")),
                Flux.just(
                        new ModelEvent.TextDelta("Isso não existe no Java 17 [1]. No Java 21 existe [2]."),
                        new ModelEvent.Completed()));

        service(model).answer(new AnswerRequest("diferença entre java 17 e java 21", Optional.empty(), ACTOR), sink);

        assertThat(sink.events)
                .doesNotContain("text:Isso não existe no Java 17 [1].")
                .contains("text:No Java 21 existe [2].");
        assertThat(sink.metadata.get(1).droppedSentences()).isEqualTo(1);
    }

    private void stubPerVersion(Map<String, List<ScoredChunk>> byVersion) {
        when(retriever.retrieve(anyString(), any(), anyInt())).thenAnswer(call -> {
            SearchFilter filter = call.getArgument(1);
            int limit = call.getArgument(2);
            String key =
                    filter.versions().isEmpty() ? "interno" : filter.versions().get(0);
            List<ScoredChunk> all = byVersion.getOrDefault(key, List.of());
            return all.subList(0, Math.min(limit, all.size()));
        });
    }

    private List<AuditEvent> auditEvents() {
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit, times(2)).record(captor.capture());
        return captor.getAllValues();
    }

    private AnswerService service(LanguageModel model, AliasCatalog names) {
        return new AnswerService(
                new QuestionAnalyzer(names),
                names,
                retriever,
                index,
                new RouteDecider(index, RETRIEVAL, true),
                new PromptBuilder(),
                model,
                audit,
                MODELS,
                CLOCK);
    }

    private AnswerService service(LanguageModel model) {
        return new AnswerService(
                analyzer,
                catalog,
                retriever,
                index,
                new RouteDecider(index, RETRIEVAL, true),
                new PromptBuilder(),
                model,
                audit,
                MODELS,
                CLOCK);
    }

    private static ScoredChunk chunk(String docId, String version, double score) {
        return new ScoredChunk(
                new Chunk(
                        "c-" + docId.hashCode(),
                        DocumentId.of(docId),
                        Source.PUBLIC,
                        "java",
                        Optional.of(version),
                        "Seção",
                        "conteúdo",
                        0,
                        true),
                score);
    }

    /** Modelo com uma resposta por faixa, que guarda as faixas e os prompts na ordem das chamadas. */
    private static final class TieredModel implements LanguageModel {
        private final Flux<ModelEvent> fast;
        private final Flux<ModelEvent> strong;
        final List<ModelTier> tiers = new ArrayList<>();
        final List<Prompt> prompts = new ArrayList<>();

        TieredModel(Flux<ModelEvent> fast, Flux<ModelEvent> strong) {
            this.fast = fast;
            this.strong = strong;
        }

        @Override
        public synchronized Flux<ModelEvent> stream(ModelTier tier, Prompt prompt) {
            tiers.add(tier);
            prompts.add(prompt);
            return tier == ModelTier.FAST ? fast : strong;
        }
    }
}
