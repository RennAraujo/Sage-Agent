package io.github.rennaraujo.sage.question;

import io.github.rennaraujo.sage.question.llm.ModelTier;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Metadados da resposta, enviados antes do texto e de novo no fim (com frases descartadas e tempos finais).
 * {@code versionUsed} é a versão pública usada no filtro da busca, vazia quando não houve uma só. {@code consulted} só
 * é preenchido no fim: são as fontes que as frases aprovadas citaram, as mesmas das linhas "Documentação consultada".
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
        Map<String, Long> timingsMillis,
        List<ConsultedSource> consulted) {

    public AnswerMetadata {
        citations = List.copyOf(citations);
        consulted = List.copyOf(consulted);
        timingsMillis = Collections.unmodifiableMap(new LinkedHashMap<>(timingsMillis));
    }
}
