package io.github.rennaraujo.sage.search;

import io.github.rennaraujo.sage.document.Source;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Restrições da busca. {@code sources} vazio significa as duas fontes. {@code language} e {@code versions} restringem
 * somente trechos PÚBLICOS (vazios = sem restrição); trechos INTERNOS passam apenas por estarem em {@code sources}.
 * Ou seja: (PUBLICO e linguagem e versão em versions) ou (INTERNO), cada ramo presente se a fonte foi pedida.
 */
public record SearchFilter(Set<Source> sources, Optional<String> language, List<String> versions) {

    public SearchFilter {
        Objects.requireNonNull(sources, "sources");
        Objects.requireNonNull(language, "language");
        Objects.requireNonNull(versions, "versions");
        sources = Set.copyOf(sources);
        versions = List.copyOf(versions);
    }

    public static SearchFilter none() {
        return new SearchFilter(Set.of(), Optional.empty(), List.of());
    }
}
