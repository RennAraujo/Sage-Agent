package io.github.rennaraujo.sage.upload;

/** Tipo de arquivo fora dos aceitos (vira 415). A mensagem é fixa e nunca cita o conteúdo. */
public class UnsupportedDocumentException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public UnsupportedDocumentException(String message) {
        super(message);
    }
}
