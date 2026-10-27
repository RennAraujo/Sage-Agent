package io.github.rennaraujo.sage.web;

import java.net.URI;
import java.util.List;
import java.util.Set;

/** Usada quando não há chave do Brave: o fallback web fica inerte e a pergunta segue o caminho sem fonte. */
final class NoopWebSearch implements WebSearch {

    @Override
    public List<URI> search(String query, Set<String> allowedDomains, int limit) {
        return List.of();
    }

    @Override
    public boolean available() {
        return false;
    }
}
