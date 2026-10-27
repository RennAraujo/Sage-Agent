package io.github.rennaraujo.sage.crypto;

/** Falha de criptografia. A mensagem nunca carrega chave nem conteúdo em claro. */
public class CryptoException extends RuntimeException {

    public CryptoException(String message) {
        super(message);
    }

    public CryptoException(String message, Throwable cause) {
        super(message, cause);
    }
}
