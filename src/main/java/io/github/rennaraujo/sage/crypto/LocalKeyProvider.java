package io.github.rennaraujo.sage.crypto;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;

/** Chave mestra de 32 bytes lida de um arquivo (base64). Para desenvolvimento e ambientes sem KMS. */
public final class LocalKeyProvider implements KeyProvider {

    private static final byte[] WRAP_AAD = "sage:v1:wrap".getBytes(StandardCharsets.US_ASCII);

    private final byte[] masterKey;

    public LocalKeyProvider(byte[] masterKey) {
        if (masterKey.length != AesGcm.KEY_BYTES) {
            throw new IllegalStateException("A chave mestra local precisa ter exatamente " + AesGcm.KEY_BYTES
                    + " bytes (AES-256), mas tem " + masterKey.length);
        }
        this.masterKey = masterKey.clone();
    }

    public static LocalKeyProvider fromFile(Path file) {
        String content;
        try {
            content = Files.readString(file, StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Não consegui ler a chave mestra local em " + file + ". Gere uma com scripts/generate-local-key",
                    e);
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(content);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("O arquivo da chave mestra local " + file + " não está em base64", e);
        }
        try {
            return new LocalKeyProvider(decoded);
        } finally {
            Arrays.fill(decoded, (byte) 0);
        }
    }

    @Override
    public DataKey generateDataKey() {
        byte[] plaintext = AesGcm.newKey();
        try {
            byte[] iv = AesGcm.newIv();
            byte[] wrapped = AesGcm.encrypt(masterKey, iv, WRAP_AAD, plaintext);
            byte[] encrypted = new byte[iv.length + wrapped.length];
            System.arraycopy(iv, 0, encrypted, 0, iv.length);
            System.arraycopy(wrapped, 0, encrypted, iv.length, wrapped.length);
            return new DataKey(plaintext, encrypted);
        } catch (RuntimeException e) {
            Arrays.fill(plaintext, (byte) 0);
            throw e;
        }
    }

    @Override
    public byte[] decryptDataKey(byte[] encryptedKey) {
        if (encryptedKey.length <= AesGcm.IV_BYTES) {
            throw new CryptoException("Chave de dados cifrada inválida");
        }
        byte[] iv = Arrays.copyOfRange(encryptedKey, 0, AesGcm.IV_BYTES);
        byte[] wrapped = Arrays.copyOfRange(encryptedKey, AesGcm.IV_BYTES, encryptedKey.length);
        return AesGcm.decrypt(masterKey, iv, WRAP_AAD, wrapped);
    }
}
