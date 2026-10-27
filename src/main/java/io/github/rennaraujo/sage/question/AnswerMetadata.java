package io.github.rennaraujo.sage.question;

import io.github.rennaraujo.sage.question.llm.ModelTier;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Metadados da resposta, enviados antes do texto e de novo no fim (com frases descartadas e tempos finais).
 * {@code versionUsed} é a versão pública usada no filtro da busca, vazia quando não houve uma só.
 */
public record AnswerMetadata(
        String conversationId,
        Route route,
        Optional<ModelTier> model,
        double topScore,
        List<Citation> citations,
        Optional<String> versionUsed,
        boolean unreviewedSources,
        int droppedSentences,
        Map<String, Long> timingsMillis) {

    public AnswerMetadata {
        citations = List.copyOf(citations);
        timingsMillis = Collections.unmodifiableMap(new LinkedHashMap<>(timingsMillis));
    }
}
