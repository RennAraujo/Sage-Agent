package io.github.rennaraujo.sage.crypto;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Criptografia em envelope: uma chave de dados nova por mensagem, embrulhada pelo {@link KeyProvider}. */
public final class EnvelopeCipher {

    private static final byte[] AAD = "sage:v1".getBytes(StandardCharsets.US_ASCII);

    private final KeyProvider keyProvider;

    public EnvelopeCipher(KeyProvider keyProvider) {
        this.keyProvider = keyProvider;
    }

    public EncryptedBlob encrypt(byte[] plaintext) {
        DataKey dataKey = keyProvider.generateDataKey();
        try {
            byte[] iv = AesGcm.newIv();
            byte[] ciphertext = AesGcm.encrypt(dataKey.plaintext(), iv, AAD, plaintext);
            return new EncryptedBlob(dataKey.encrypted(), iv, ciphertext);
        } finally {
            Arrays.fill(dataKey.plaintext(), (byte) 0);
        }
    }

    public byte[] decrypt(EncryptedBlob blob) {
        byte[] key = keyProvider.decryptDataKey(blob.encryptedDataKey());
        try {
            return AesGcm.decrypt(key, blob.iv(), AAD, blob.ciphertext());
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }
}
