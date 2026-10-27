package io.github.rennaraujo.sage.web;

import java.net.URI;

/**
 * Retrato de uma importação num instante. {@code skipped} são páginas que não entraram (segredo, vazia, fora do
 * escopo, documento que já existia); {@code collisions}, páginas cujo slug caiu num id que outra página do mesmo job
 * já tinha ocupado.
 */
record ImportJob(
        String id,
        URI root,
        String language,
        String version,
        Status status,
        int imported,
        int skipped,
        int failed,
        int collisions) {

    enum Status {
        RUNNING,
        DONE,
        FAILED
    }
}
