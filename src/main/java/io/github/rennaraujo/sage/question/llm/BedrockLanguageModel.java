package io.github.rennaraujo.sage.question.llm;

import java.util.List;
import java.util.Objects;
import org.springframework.ai.bedrock.converse.BedrockChatOptions;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;

/**
 * Adaptador para o Bedrock Converse via Spring AI, em streaming. O model id sai da faixa pedida (FAST ou STRONG), com
 * temperatura e teto de tokens de {@code sage.models}. Não registra nada do prompt nem da resposta.
 */
public final class BedrockLanguageModel implements LanguageModel {

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
        BedrockChatOptions options = BedrockChatOptions.builder()
                .model(modelId)
                .temperature(properties.temperature())
                .maxTokens(properties.maxTokens())
                .build();
        org.springframework.ai.chat.prompt.Prompt request = new org.springframework.ai.chat.prompt.Prompt(
                List.of(new SystemMessage(prompt.system()), new UserMessage(prompt.user())), options);
        Flux<ModelEvent> text = chatModel.stream(request)
                .map(BedrockLanguageModel::textOf)
                .filter(s -> !s.isEmpty())
                .map(ModelEvent.TextDelta::new);
        return text.concatWithValues(new ModelEvent.Completed());
    }

    private static String textOf(ChatResponse response) {
        Generation generation = response.getResult();
        if (generation == null) {
            return "";
        }
        AssistantMessage output = generation.getOutput();
        return output == null ? "" : Objects.requireNonNullElse(output.getText(), "");
    }
}
