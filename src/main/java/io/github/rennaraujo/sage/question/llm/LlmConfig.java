package io.github.rennaraujo.sage.question.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Flux;

/**
 * Liga o {@link LanguageModel} ao Bedrock quando há um {@link ChatModel} do Spring AI e os dois model ids. Sem eles a
 * aplicação sobe do mesmo jeito (busca, upload e saúde funcionam) e {@code /api/perguntas} responde 503 "modelo não
 * configurado". Preferimos isso a falhar no boot porque o índice e o upload não dependem do modelo, e um ambiente de
 * desenvolvimento sem AWS continua útil.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ModelProperties.class)
public class LlmConfig {

    private static final Logger LOG = LoggerFactory.getLogger(LlmConfig.class);

    @Bean
    LanguageModel languageModel(ObjectProvider<ChatModel> chatModels, ModelProperties properties) {
        ChatModel chat = chatModels.getIfAvailable();
        if (chat == null || !properties.configured()) {
            LOG.warn("Modelo de linguagem não configurado (spring.ai.model.chat=bedrock-converse e "
                    + "SAGE_MODEL_FAST_ID/SAGE_MODEL_STRONG_ID): /api/perguntas vai responder 503");
            return new Unavailable();
        }
        return new BedrockLanguageModel(chat, properties);
    }

    /** Sem modelo: {@code available()} falso, e o stream falha se alguém insistir. */
    static final class Unavailable implements LanguageModel {

        @Override
        public Flux<ModelEvent> stream(ModelTier tier, Prompt prompt) {
            return Flux.error(new IllegalStateException("Modelo de linguagem não configurado"));
        }

        @Override
        public boolean available() {
            return false;
        }
    }
}
