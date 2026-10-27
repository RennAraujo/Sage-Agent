package io.github.rennaraujo.sage.search.rerank;

import io.github.rennaraujo.sage.search.Chunk;
import io.github.rennaraujo.sage.search.ScoredChunk;
import java.util.List;

/** Reordena candidatos olhando pergunta e trecho juntos. O score é uma probabilidade aproximada em [0, 1]. */
public interface Reranker {

    /** Um {@link ScoredChunk} por candidato, do mais para o menos relevante. Sem candidatos, lista vazia. */
    List<ScoredChunk> rerank(String query, List<Chunk> candidates);
}
