package io.github.rennaraujo.sage.question.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;

class BedrockLanguageModelTest {

    private static final ModelProperties PROPERTIES =
            new ModelProperties("haiku-id", "sonnet-id", 1500, 0.0, Duration.ofSeconds(30));

    private final ChatModel chat = mock(ChatModel.class);
    private final BedrockLanguageModel model = new BedrockLanguageModel(chat, PROPERTIES);

    @Test
    void mapsToolCallToEscalationRequested() {
        AssistantMessage toolCall = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "t1", "function", "escalate", "{\"reason\":\"precisa comparar fontes\"}")))
                .build();
        when(chat.stream(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenReturn(Flux.just(response(toolCall)));

        List<ModelEvent> events = model.stream(ModelTier.FAST, new Prompt("sistema", "usuario", true))
                .collectList()
                .block();

        assertThat(events)
                .containsExactly(
                        new ModelEvent.EscalationRequested("precisa comparar fontes"), new ModelEvent.Completed());
    }

    @Test
    void textAndToolCallInSameResponseKeepOrder() {
        AssistantMessage both = AssistantMessage.builder()
                .content("Antes ")
                .toolCalls(List.of(new AssistantMessage.ToolCall("t1", "function", "escalate", "nao-e-json")))
                .build();
        when(chat.stream(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(Flux.just(response(both)));

        List<ModelEvent> events = model.stream(ModelTier.FAST, new Prompt("s", "u", true))
                .collectList()
                .block();

        assertThat(events)
                .containsExactly(
                        new ModelEvent.TextDelta("Antes "),
                        new ModelEvent.EscalationRequested(""),
                        new ModelEvent.Completed());
    }

    @Test
    void otherToolNamesAreIgnored() {
        AssistantMessage other = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("t1", "function", "rm_rf", "{}")))
                .build();
        when(chat.stream(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(Flux.just(response(other)));

        List<ModelEvent> events = model.stream(ModelTier.FAST, new Prompt("s", "u", true))
                .collectList()
                .block();

        assertThat(events).containsExactly(new ModelEvent.Completed());
    }

    @Test
    void declaresEscalateOnlyWhenAllowed() {
        when(chat.stream(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenReturn(Flux.just(response(new AssistantMessage("Oi [1]."))));
        ArgumentCaptor<org.springframework.ai.chat.prompt.Prompt> sent =
                ArgumentCaptor.forClass(org.springframework.ai.chat.prompt.Prompt.class);

        model.stream(ModelTier.FAST, new Prompt("s", "u", true)).blockLast();
        model.stream(ModelTier.STRONG, new Prompt("s", "u", false)).blockLast();

        verify(chat, org.mockito.Mockito.times(2)).stream(sent.capture());
        ToolCallingChatOptions fast =
                (ToolCallingChatOptions) sent.getAllValues().get(0).getOptions();
        ToolCallingChatOptions strong =
                (ToolCallingChatOptions) sent.getAllValues().get(1).getOptions();
        assertThat(fast.getModel()).isEqualTo("haiku-id");
        assertThat(fast.getToolCallbacks())
                .extracting(cb -> cb.getToolDefinition().name())
                .containsExactly("escalate");
        assertThat(fast.getToolCallbacks().get(0).getToolDefinition().inputSchema())
                .contains("\"reason\"");
        assertThat(strong.getModel()).isEqualTo("sonnet-id");
        assertThat(strong.getToolCallbacks()).isNullOrEmpty();
    }

    @Test
    void escalateIsNeverExecutedLocally() {
        ToolCallback escalate = BedrockLanguageModel.ESCALATE_TOOL;

        assertThatThrownBy(() -> escalate.call("{\"reason\":\"x\"}")).isInstanceOf(IllegalStateException.class);
    }

    private static ChatResponse response(AssistantMessage message) {
        return new ChatResponse(List.of(new Generation(message)));
    }
}
