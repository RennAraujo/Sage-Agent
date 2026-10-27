package io.github.rennaraujo.sage.question;

import io.github.rennaraujo.sage.document.DocumentId;
import java.util.Objects;
import java.util.Optional;

/**
 * Trecho enviado ao modelo, que a resposta cita como {@code [index]}. {@code language} e {@code version} deixam a
 * interface mostrar "[1] Java 21 · Stream API"; em documento interno, {@code language} é o nome da aplicação e a
 * versão fica vazia.
 */
public record Citation(
        int index, DocumentId documentId, String section, boolean reviewed, String language, Optional<String> version) {

    public Citation {
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(section, "section");
        Objects.requireNonNull(language, "language");
        Objects.requireNonNull(version, "version");
    }
}
