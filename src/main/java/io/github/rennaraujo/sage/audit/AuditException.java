package io.github.rennaraujo.sage.audit;

/** Falha ao gravar um evento. Mensagem fixa e sem causa: nada do evento nem da exceção original vaza. */
public class AuditException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AuditException() {
        super("Não foi possível gravar o evento de auditoria");
    }
}
