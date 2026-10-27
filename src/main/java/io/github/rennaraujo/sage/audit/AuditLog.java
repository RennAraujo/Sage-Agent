package io.github.rennaraujo.sage.audit;

public interface AuditLog {

    /** Registra o evento ou lança {@link AuditException}; nunca engole a falha em silêncio. */
    void record(AuditEvent event);
}
