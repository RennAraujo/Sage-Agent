package io.github.rennaraujo.sage.web;

import java.net.URI;
import java.util.List;
import java.util.Set;

/** Busca web restrita a domínios oficiais. Implementações devolvem só URLs https de hosts da allowlist. */
public interface WebSearch {

    List<URI> search(String query, Set<String> allowedDomains, int limit);

    /** {@code false} quando não há buscador de verdade (sem chave): o fallback web fica desligado. */
    default boolean available() {
        return true;
    }
}
