package io.github.rennaraujo.sage.document;

/** Documento ou id fora do formato esperado. A mensagem nunca ecoa o conteúdo recebido. */
public class InvalidDocumentException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    public InvalidDocumentException(String message) {
        super(message);
    }
}
