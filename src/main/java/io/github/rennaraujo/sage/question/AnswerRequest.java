package io.github.rennaraujo.sage.question;

import java.util.Objects;
import java.util.Optional;

/** Pergunta já validada pelo controller. {@code actor} é o nome da chave de API. O texto nunca vai para log. */
public record AnswerRequest(String question, Optional<String> conversationId, String actor) {

    public AnswerRequest {
        Objects.requireNonNull(question, "question");
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(actor, "actor");
    }

    @Override
    public String toString() {
        return "AnswerRequest[" + question.length() + " chars]";
    }
}
