package io.github.rennaraujo.sage.question;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.rennaraujo.sage.audit.AuditEvent;
import io.github.rennaraujo.sage.audit.AuditLog;
import io.github.rennaraujo.sage.document.DocumentId;
import io.github.rennaraujo.sage.document.Source;
import io.github.rennaraujo.sage.question.AnswerServiceTest.RecordingSink;
import io.github.rennaraujo.sage.question.llm.FakeLanguageModel;
import io.github.rennaraujo.sage.question.llm.LanguageModel;
import io.github.rennaraujo.sage.question.llm.ModelProperties;
import io.github.rennaraujo.sage.question.llm.ModelTier;
import io.github.rennaraujo.sage.question.llm.PromptBuilder;
import io.github.rennaraujo.sage.search.Chunk;
import io.github.rennaraujo.sage.search.RetrievalProperties;
import io.github.rennaraujo.sage.search.Retriever;
import io.github.rennaraujo.sage.search.ScoredChunk;
import io.github.rennaraujo.sage.search.SearchIndex;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AnswerServiceFlowTest {

    private static final String QUESTION = "Como funciona o fluxo de faturamento no portal-x?";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-27T12:00:00Z"), ZoneOffset.UTC);
    private static final ModelProperties MODELS =
            new ModelProperties("haiku-id", "sonnet-id", 1500, 0.0, Duration.ofSeconds(30));
    private static final RetrievalProperties RETRIEVAL = new RetrievalProperties(1500, 20, 5, 0.35, 0.0, 8);
    private static final String MERMAID = "```mermaid\nflowchart TD\n  A[Pedido] --> B[Nota]\n  B --> C[Cobranca]\n```";

    private final SearchIndex index = mock(SearchIndex.class);
    private final Retriever retriever = mock(Retriever.class);
    private final AuditLog audit = mock(AuditLog.class);
    private final AliasCatalog catalog =
            new AliasCatalog(new FakeCatalogs.Store(FakeCatalogs.internal("portal-x", List.of("Faturamento X"))));
    private final RecordingSink sink = new RecordingSink();

    @BeforeEach
    void setUp() {
        Chunk fechamento = chunk(
                "interno/portal-x/faturamento.md",
                0,
                "Faturamento",
                "O fechamento acontece no dia 5. Veja [[cobranca]].");
        when(index.chunksOf(DocumentId.of("interno/portal-x/faturamento.md"))).thenReturn(List.of(fechamento));
        Chunk cobranca = chunk("interno/portal-x/cobranca.md", 0, "Cobrança", "A cobrança sai no dia seguinte.");
        when(index.chunksOf(DocumentId.of("interno/portal-x/cobranca.md"))).thenReturn(List.of(cobranca));
        when(retriever.retrieve(anyString(), any())).thenReturn(List.of(new ScoredChunk(fechamento, 0.9)));
    }

    @Test
    void flowRoutesToStrongWithExpandedNumberedContext() {
        FakeLanguageModel model = new FakeLanguageModel("1. Fecha no dia 5 [1].\n2. Cobra no dia seguinte [2].\n");

        service(model).answer(new AnswerRequest(QUESTION, Optional.empty(), "leitor"), sink);

        assertThat(sink.metadata.get(0).route()).isEqualTo(Route.STRONG_MODEL);
        assertThat(sink.metadata.get(0).model()).contains(ModelTier.STRONG);
        assertThat(sink.metadata.get(0).topScore()).isEqualTo(0.9);
        assertThat(sink.metadata.get(0).citations()).hasSize(2);
        assertThat(model.tiers()).containsExactly(ModelTier.STRONG);
        assertThat(model.prompts().get(0).allowEscalation()).isFalse();
        assertThat(model.prompts().get(0).system()).contains("passos numerados");
        assertThat(model.prompts().get(0).user())
                .contains("<documento n=\"1\" fonte=\"interno/portal-x/faturamento.md\"")
                .contains("<documento n=\"2\" fonte=\"interno/portal-x/cobranca.md\"")
                .contains("A cobrança sai no dia seguinte.");
        assertThat(sink.events)
                .containsExactly(
                        "metadata",
                        "text:Documentação consultada: portal-x (interna)",
                        "text:1. Fecha no dia 5 [1].",
                        "text:2. Cobra no dia seguinte [2].",
                        "done");
        // A expansão é só do prompt: a busca recebe a pergunta como veio.
        ArgumentCaptor<String> asked = ArgumentCaptor.forClass(String.class);
        verify(retriever, times(1)).retrieve(asked.capture(), any());
        assertThat(asked.getValue()).isEqualTo(QUESTION);
        ArgumentCaptor<AuditEvent> events = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit, times(2)).record(events.capture());
        assertThat(events.getAllValues().get(0).details())
                .containsEntry("contextDocIds", "interno/portal-x/faturamento.md,interno/portal-x/cobranca.md");
    }

    @Test
    void numberedStepWithoutCitationDropped() {
        FakeLanguageModel model = new FakeLanguageModel(
                "1. Fecha no dia 5 [1].\n", "2. Passo sem fonte.\n", "3. Cobra no dia seguinte [2].\n", MERMAID + "\n");

        service(model).answer(new AnswerRequest(QUESTION, Optional.empty(), "leitor"), sink);

        assertThat(sink.events)
                .containsExactly(
                        "metadata",
                        "text:Documentação consultada: portal-x (interna)",
                        "text:1. Fecha no dia 5 [1].",
                        "text:3. Cobra no dia seguinte [2].",
                        "text:" + MERMAID,
                        "done");
        assertThat(sink.metadata.get(1).droppedSentences()).isEqualTo(1);
    }

    @Test
    void mermaidWithClickDirectiveNeverReachesTheUser() {
        String evil = "```mermaid\nflowchart TD\n  A --> B\n  click A href \"https://evil.example\"\n```";
        FakeLanguageModel model = new FakeLanguageModel("1. Fecha no dia 5 [1].\n", evil + "\n");

        service(model).answer(new AnswerRequest(QUESTION, Optional.empty(), "leitor"), sink);

        assertThat(sink.events).noneMatch(e -> e.contains("click") || e.contains("href") || e.contains("mermaid"));
    }

    @Test
    void noRelevantChunkSaysTheFlowIsNotDetailedWithoutCallingModel() {
        when(retriever.retrieve(anyString(), any()))
                .thenReturn(List.of(
                        new ScoredChunk(chunk("interno/portal-x/faturamento.md", 0, "Faturamento", "texto"), 0.1)));
        LanguageModel model = mock(LanguageModel.class);

        service(model).answer(new AnswerRequest(QUESTION, Optional.empty(), "leitor"), sink);

        verifyNoInteractions(model);
        assertThat(sink.events).containsExactly("metadata", "text:A documentação não detalha esse fluxo.", "done");
        assertThat(sink.metadata.get(0).route()).isEqualTo(Route.NOT_DOCUMENTED);
    }

    @Test
    void modelSayingTheFlowIsNotDetailedGetsTheFixedText() {
        FakeLanguageModel model = new FakeLanguageModel("A documentação não detalha esse fluxo.");

        service(model).answer(new AnswerRequest(QUESTION, Optional.empty(), "leitor"), sink);

        assertThat(sink.events).contains("text:A documentação não detalha esse fluxo.");
        assertThat(sink.events).doesNotContain("text:A documentação disponível não cobre isso.");
    }

    private AnswerService service(LanguageModel model) {
        return new AnswerService(
                new QuestionAnalyzer(catalog),
                catalog,
                retriever,
                index,
                new RouteDecider(index, RETRIEVAL, true),
                new PromptBuilder(),
                new FlowContextExpander(index, new FlowProperties(12000, 3, 24000)),
                model,
                audit,
                MODELS,
                CLOCK);
    }

    private static Chunk chunk(String docId, int ordinal, String heading, String text) {
        return new Chunk(
                docId + "#" + ordinal,
                DocumentId.of(docId),
                Source.INTERNAL,
                "portal-x",
                Optional.empty(),
                heading,
                text,
                ordinal,
                true);
    }
}
