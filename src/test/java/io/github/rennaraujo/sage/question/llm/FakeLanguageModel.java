package io.github.rennaraujo.sage.question.llm;

import java.util.ArrayList;
import java.util.List;
import reactor.core.publisher.Flux;

/** Modelo de linguagem de mentira: devolve os deltas combinados e guarda os prompts recebidos. */
public final class FakeLanguageModel implements LanguageModel {

    private final List<String> deltas;
    private final List<Prompt> prompts = new ArrayList<>();
    private final List<ModelTier> tiers = new ArrayList<>();

    public FakeLanguageModel(String... deltas) {
        this.deltas = List.of(deltas);
    }

    @Override
    public synchronized Flux<ModelEvent> stream(ModelTier tier, Prompt prompt) {
        tiers.add(tier);
        prompts.add(prompt);
        Flux<ModelEvent> text = Flux.fromIterable(deltas).map(ModelEvent.TextDelta::new);
        return text.concatWithValues(new ModelEvent.Completed());
    }

    public synchronized List<Prompt> prompts() {
        return List.copyOf(prompts);
    }

    public synchronized List<ModelTier> tiers() {
        return List.copyOf(tiers);
    }
}
