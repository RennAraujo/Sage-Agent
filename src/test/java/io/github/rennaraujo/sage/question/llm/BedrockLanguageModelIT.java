package io.github.rennaraujo.sage.question.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.bedrock.converse.BedrockProxyChatModel;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;

/**
 * Chama o Bedrock de verdade: só roda com {@code SAGE_BEDROCK_IT=true}, credenciais AWS no ambiente e
 * {@code SAGE_MODEL_FAST_ID} com um model id (ou inference profile) habilitado na conta.
 */
@EnabledIfEnvironmentVariable(named = "SAGE_BEDROCK_IT", matches = "true")
class BedrockLanguageModelIT {

    private static final int MAX_TOKENS = 200;
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    @Test
    void streamsNonEmptyText() {
        String fastId = System.getenv("SAGE_MODEL_FAST_ID");
        assertThat(fastId).as("SAGE_MODEL_FAST_ID").isNotBlank();
        Region region = new DefaultAwsRegionProviderChain().getRegion();
        BedrockProxyChatModel chat =
                BedrockProxyChatModel.builder().region(region).build();
        ModelProperties properties = new ModelProperties(fastId, fastId, MAX_TOKENS, 0.0, TIMEOUT);
        BedrockLanguageModel model = new BedrockLanguageModel(chat, properties);

        List<ModelEvent> events = model.stream(
                        ModelTier.FAST, new Prompt("Responda em pt-BR, em uma frase.", "Diga olá.", false))
                .collectList()
                .block(TIMEOUT);

        assertThat(events).isNotNull();
        String text = events.stream()
                .filter(ModelEvent.TextDelta.class::isInstance)
                .map(e -> ((ModelEvent.TextDelta) e).text())
                .reduce("", String::concat);
        assertThat(text).isNotBlank();
        assertThat(events.get(events.size() - 1)).isInstanceOf(ModelEvent.Completed.class);
    }
}
