package io.github.rennaraujo.sage.document;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

public interface DocumentStore {

    /** Grava ou substitui o documento de mesmo id. */
    void save(Document doc);

    /**
     * Grava só se o id ainda não existe, de forma atômica. Devolve {@code false}, sem alterar nada nem publicar evento,
     * quando o id já estava no acervo.
     */
    boolean saveIfAbsent(Document doc);

    Optional<Document> find(DocumentId id);

    List<DocumentMetadata> listMetadata();

    void delete(DocumentId id);

    /** Documentos decifrados, um a um, sob demanda. */
    Stream<Document> loadAll();
}
