package io.github.rennaraujo.sage.search;

import java.util.Objects;

/** Trecho com a nota de relevância para a pergunta, sempre em [0, 1]. */
public record ScoredChunk(Chunk chunk, double score) {

    public ScoredChunk {
        Objects.requireNonNull(chunk, "chunk");
        if (!(score >= 0.0 && score <= 1.0)) {
            throw new IllegalArgumentException("score fora de [0,1]: " + score);
        }
    }
}
