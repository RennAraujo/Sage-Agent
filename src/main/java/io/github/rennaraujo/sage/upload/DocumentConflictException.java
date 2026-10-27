package io.github.rennaraujo.sage.upload;

/** O id já existe e o envio não pediu substituição (vira 409). A mensagem não repete o id. */
public class DocumentConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DocumentConflictException() {
        super("Já existe documento com esse id; envie substituir=true para sobrescrever");
    }
}
