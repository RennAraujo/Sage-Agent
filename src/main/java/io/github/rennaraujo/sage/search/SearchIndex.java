package io.github.rennaraujo.sage.search;

import io.github.rennaraujo.sage.document.Document;
import io.github.rennaraujo.sage.document.DocumentId;
import java.util.List;
import java.util.SortedSet;

/** Porta de busca. O núcleo não conhece Lucene; só esta interface. */
public interface SearchIndex {

    /** Indexa o documento, substituindo os trechos anteriores do mesmo id. Idempotente. */
    void upsert(Document doc);

    /** Como {@link #upsert}, mas sem atualizar o leitor: para carga em lote, fechada por {@link #refresh()}. */
    default void upsertDeferred(Document doc) {
        upsert(doc);
    }

    /** Torna visíveis à busca os upserts adiados. */
    default void refresh() {}

    /** Remove todos os trechos do documento. Idempotente. */
    void remove(DocumentId id);

    /** Melhores trechos por BM25, mais relevante primeiro. Consulta vazia ou {@code limit <= 0} dá lista vazia. */
    List<Chunk> search(String query, SearchFilter filter, int limit);

    /** Trechos de um documento, na ordem em que aparecem nele. */
    List<Chunk> chunksOf(DocumentId id);

    /** Versões indexadas da linguagem, em ordem semântica ("4.9" antes de "4.10"). */
    SortedSet<String> versionsOf(String language);

    boolean isReady();
}
