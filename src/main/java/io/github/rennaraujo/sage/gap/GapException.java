package io.github.rennaraujo.sage.gap;

/** Falha ao gravar uma lacuna. Sem mensagem nem causa: ambas poderiam carregar o texto da pergunta. */
public final class GapException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    GapException() {
        super("Falha ao gravar a lacuna", null, false, false);
    }
}
