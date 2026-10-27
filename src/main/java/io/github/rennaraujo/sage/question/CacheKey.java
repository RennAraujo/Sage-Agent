package io.github.rennaraujo.sage.question;

import io.github.rennaraujo.sage.document.Source;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Chave do cache de respostas. {@code normalizedQuestion} vem de {@link AnswerCache#normalize}; {@code versions} são
 * as versões efetivamente usadas (a inferida, quando a pergunta não trouxe nenhuma). O texto da pergunta fica só na
 * memória e nunca aparece em {@code toString}.
 */
public record CacheKey(
        String normalizedQuestion,
        Set<Source> sources,
        Optional<String> language,
        List<String> versions,
        QuestionType type) {

    public CacheKey {
        sources = Set.copyOf(sources);
        versions = List.copyOf(versions);
    }

    @Override
    public String toString() {
        return "CacheKey[" + normalizedQuestion.length() + " chars]";
    }
}
