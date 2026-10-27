package io.github.rennaraujo.sage.crypto;

/**
 * Chave de dados AES-256 em duas formas: {@code plaintext} para uso imediato (quem recebe deve zerar o array) e
 * {@code encrypted} para guardar junto do dado cifrado.
 */
public record DataKey(byte[] plaintext, byte[] encrypted) {

    @Override
    public String toString() {
        return "DataKey[redacted]";
    }
}
