package io.github.rennaraujo.sage.question.llm;

import reactor.core.publisher.Flux;

/** Porta para o modelo de linguagem. O núcleo não conhece Bedrock nem Spring AI; só esta interface. */
public interface LanguageModel {

    /** Resposta em streaming; termina com {@link ModelEvent.Completed} ou com erro. */
    Flux<ModelEvent> stream(ModelTier tier, Prompt prompt);

    /** {@code false} quando não há modelo configurado (faltam os model ids): a API responde 503. */
    default boolean available() {
        return true;
    }
}
