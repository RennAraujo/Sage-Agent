package io.github.rennaraujo.sage.question.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.ai.bedrock.converse.BedrockChatOptions;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.util.json.JsonParser;
import reactor.core.publisher.Flux;

/**
 * Adaptador para o Bedrock Converse via Spring AI, em streaming. O model id sai da faixa pedida (FAST ou STRONG), com
 * temperatura e teto de tokens de {@code sage.models}. Não registra nada do prompt nem da resposta.
 *
 * <p>Quando o prompt permite escalada, declara a ferramenta {@code escalate(reason)}. Ela nunca roda aqui: uma chamada
 * dela vira {@link ModelEvent.EscalationRequested} e quem decide o que fazer é o {@code AnswerService}. No Spring AI
 * 2.0 o {@code ChatModel} não executa ferramentas sozinho (isso ficou com o {@code ChatClient} e seus advisors, que não
 * usamos), então não existe mais a opção {@code internalToolExecutionEnabled}: chamar o {@code ChatModel} direto já é
 * o "desligado". O {@code call} da ferramenta falha de propósito, para o caso de alguém um dia ligá-la num
 * {@code ChatClient}.
 */
public final class BedrockLanguageModel implements LanguageModel {

    static final String ESCALATE = "escalate";

    static final ToolCallback ESCALATE_TOOL = new EscalateTool();

    private final ChatModel chatModel;
    private final ModelProperties properties;

    public BedrockLanguageModel(ChatModel chatModel, ModelProperties properties) {
        this.chatModel = chatModel;
        this.properties = properties;
    }

    @Override
    public boolean available() {
        return properties.configured();
    }

    @Override
    public Flux<ModelEvent> stream(ModelTier tier, Prompt prompt) {
        String modelId = properties
                .idFor(tier)
                .orElseThrow(() -> new IllegalStateException("Model id não configurado para a faixa " + tier));
        BedrockChatOptions.Builder options = BedrockChatOptions.builder()
                .model(modelId)
                .temperature(properties.temperature())
                .maxTokens(properties.maxTokens());
        if (prompt.allowEscalation()) {
            options.toolCallbacks(List.of(ESCALATE_TOOL));
        }
        org.springframework.ai.chat.prompt.Prompt request = new org.springframework.ai.chat.prompt.Prompt(
                List.of(new SystemMessage(prompt.system()), new UserMessage(prompt.user())), options.build());
        return chatModel.stream(request)
                .concatMapIterable(BedrockLanguageModel::eventsOf)
                .concatWithValues(new ModelEvent.Completed());
    }

    /** Texto primeiro, depois as chamadas de {@code escalate}; outras ferramentas (não declaradas) são ignoradas. */
    private static List<ModelEvent> eventsOf(ChatResponse response) {
        Generation generation = response.getResult();
        if (generation == null || generation.getOutput() == null) {
            return List.of();
        }
        AssistantMessage output = generation.getOutput();
        List<ModelEvent> events = new ArrayList<>();
        String text = Objects.requireNonNullElse(output.getText(), "");
        if (!text.isEmpty()) {
            events.add(new ModelEvent.TextDelta(text));
        }
        for (AssistantMessage.ToolCall call : output.getToolCalls()) {
            if (ESCALATE.equals(call.name())) {
                events.add(new ModelEvent.EscalationRequested(reasonOf(call.arguments())));
            }
        }
        return events;
    }

    /** O motivo é só informativo; argumento que não é JSON vira motivo vazio, sem levar o texto para exceção. */
    private static String reasonOf(String arguments) {
        try {
            EscalateArguments parsed = JsonParser.fromJson(arguments, EscalateArguments.class);
            return parsed == null ? "" : Objects.requireNonNullElse(parsed.reason(), "");
        } catch (RuntimeException e) {
            return "";
        }
    }

    record EscalateArguments(String reason) {}

    /** Declaração da ferramenta para o Bedrock. Não é executável. */
    private static final class EscalateTool implements ToolCallback {

        private static final ToolDefinition DEFINITION = ToolDefinition.builder()
                .name(ESCALATE)
                .description("Passa a pergunta para um modelo mais forte. Chame antes de escrever qualquer texto, "
                        + "quando os documentos não bastarem ou a pergunta exigir análise entre várias fontes.")
                .inputSchema("""
                        {"type":"object","properties":{"reason":{"type":"string",\
                        "description":"Por que um modelo mais forte é necessário, em poucas palavras."}},\
                        "required":["reason"]}""")
                .build();

        @Override
        public ToolDefinition getToolDefinition() {
            return DEFINITION;
        }

        @Override
        public String call(String toolInput) {
            throw new IllegalStateException("A ferramenta escalate não é executada localmente");
        }
    }
}
