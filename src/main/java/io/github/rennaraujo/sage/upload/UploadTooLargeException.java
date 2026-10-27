package io.github.rennaraujo.sage.upload;

/** Arquivo, texto extraído, zip ou conteúdo descompactado acima dos limites (vira 413). */
public class UploadTooLargeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public UploadTooLargeException(String message) {
        super(message);
    }
}
