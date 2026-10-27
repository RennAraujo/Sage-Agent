package io.github.rennaraujo.sage.crypto;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** AES-256-GCM com IV aleatório de 12 bytes e tag de 128 bits. Compartilhado pelo envelope e pela chave local. */
final class AesGcm {

    static final int KEY_BYTES = 32;
    static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String ALGORITHM = "AES";
    private static final SecureRandom RANDOM = new SecureRandom();

    private AesGcm() {}

    static byte[] newIv() {
        byte[] iv = new byte[IV_BYTES];
        RANDOM.nextBytes(iv);
        return iv;
    }

    static byte[] newKey() {
        byte[] key = new byte[KEY_BYTES];
        RANDOM.nextBytes(key);
        return key;
    }

    static byte[] encrypt(byte[] key, byte[] iv, byte[] aad, byte[] plaintext) {
        return run(Cipher.ENCRYPT_MODE, key, iv, aad, plaintext);
    }

    static byte[] decrypt(byte[] key, byte[] iv, byte[] aad, byte[] ciphertext) {
        return run(Cipher.DECRYPT_MODE, key, iv, aad, ciphertext);
    }

    private static byte[] run(int mode, byte[] key, byte[] iv, byte[] aad, byte[] input) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(mode, new SecretKeySpec(key, ALGORITHM), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad);
            return cipher.doFinal(input);
        } catch (GeneralSecurityException e) {
            throw new CryptoException("Falha ao aplicar AES-GCM (chave, IV ou dado inválido/adulterado)", e);
        }
    }
}
