package io.github.rennaraujo.sage.audit;

import java.time.Instant;
import java.util.Map;

/**
 * Um fato auditável. {@code actor} é o nome da chave de API. {@code details} traz só ids, hashes, tamanhos e rotas;
 * nunca texto de pergunta, resposta ou documento. O {@code toString} mostra apenas o tipo e o instante, para que o
 * evento não vaze o ator nem os detalhes se alguém o logar por descuido.
 */
public record AuditEvent(AuditEventType type, String actor, Instant at, Map<String, String> details) {

    public AuditEvent {
        if (type == null || at == null || details == null) {
            throw new IllegalArgumentException("Evento de auditoria incompleto");
        }
        if (actor == null || actor.isBlank()) {
            throw new IllegalArgumentException("Evento de auditoria sem ator");
        }
        if (details.entrySet().stream().anyMatch(e -> e.getKey() == null || e.getValue() == null)) {
            throw new IllegalArgumentException("Evento de auditoria com chave ou valor nulo em details");
        }
        details = Map.copyOf(details);
    }

    @Override
    public String toString() {
        return "AuditEvent[" + type + " at " + at + "]";
    }
}
