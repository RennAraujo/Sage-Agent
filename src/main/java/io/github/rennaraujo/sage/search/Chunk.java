package io.github.rennaraujo.sage.search;

import io.github.rennaraujo.sage.document.DocumentId;
import io.github.rennaraujo.sage.document.Source;
import java.util.Objects;
import java.util.Optional;

/**
 * Trecho de um documento, a unidade que a busca devolve e que a resposta cita. O {@code id} é derivado do hash do id
 * do documento (nunca o contém) mais a posição do trecho, então é estável entre reindexações.
 */
public record Chunk(
        String id,
        DocumentId documentId,
        Source source,
        String language,
        Optional<String> version,
        String headingPath,
        String text,
        int ordinal,
        boolean reviewed) {

    public Chunk {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(language, "language");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(headingPath, "headingPath");
        Objects.requireNonNull(text, "text");
    }

    /** O texto é conteúdo do acervo: fica fora do toString para não vazar em log. */
    @Override
    public String toString() {
        return "Chunk[" + id + "]";
    }
}
