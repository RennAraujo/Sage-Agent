package io.github.rennaraujo.sage.question.llm;

import io.github.rennaraujo.sage.search.ScoredChunk;
import java.util.List;
import java.util.Objects;

/**
 * Um grupo de trechos numa pergunta de comparação, na ordem do score: os de uma versão pública, ou (com
 * {@code internal}) os da documentação interna numa comparação mista. O grupo interno não tem versão. {@code searched}
 * é falso para versão pedida que ficou de fora do teto de grupos: ela não foi buscada, então o vazio dela não quer
 * dizer nada e não gera "não menciona".
 */
public record VersionContext(String version, boolean internal, boolean searched, List<ScoredChunk> chunks) {

    public VersionContext {
        Objects.requireNonNull(version, "version");
        chunks = List.copyOf(chunks);
    }

    public VersionContext(String version, List<ScoredChunk> chunks) {
        this(version, false, true, chunks);
    }

    public static VersionContext internal(List<ScoredChunk> chunks) {
        return new VersionContext("", true, true, chunks);
    }

    public static VersionContext notSearched(String version) {
        return new VersionContext(version, false, false, List.of());
    }
}
