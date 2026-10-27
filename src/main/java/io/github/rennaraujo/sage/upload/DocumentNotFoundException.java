package io.github.rennaraujo.sage.upload;

/** Id sem documento correspondente (vira 404). A mensagem não repete o id recebido. */
public class DocumentNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DocumentNotFoundException() {
        super("Documento não encontrado");
    }
}
