package io.github.rennaraujo.sage.question;

import io.github.rennaraujo.sage.document.Source;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Resultado de entender a pergunta. {@code language} e {@code versions} descrevem só o lado público; perguntas
 * apenas internas deixam os dois vazios, porque o filtro de busca só os aplica a trechos PUBLIC.
 */
public record QuestionAnalysis(
        Set<Source> sources,
        Optional<String> language,
        List<String> versions,
        QuestionType type,
        boolean mixed,
        boolean publicLanguageMatched) {

    public QuestionAnalysis {
        sources = Set.copyOf(sources);
        versions = List.copyOf(versions);
    }
}
