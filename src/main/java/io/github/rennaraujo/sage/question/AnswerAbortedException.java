package io.github.rennaraujo.sage.question;

/**
 * A resposta foi cortada no meio: o modelo passou do prazo total ou o cliente foi embora. Mensagem fixa, sem conteúdo.
 */
public class AnswerAbortedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Por que a resposta parou. */
    public enum Reason {
        MODEL_DEADLINE,
        CLIENT_GONE
    }

    private final Reason reason;

    public AnswerAbortedException(Reason reason) {
        super("Resposta interrompida: " + reason);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
