package io.github.rennaraujo.sage.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Dado cifrado com a chave de dados embrulhada. Formato serializado: "SAG1", u16 com o tamanho da chave cifrada, a
 * chave cifrada, o IV (12 bytes) e o ciphertext (com a tag GCM no final).
 */
public record EncryptedBlob(byte[] encryptedDataKey, byte[] iv, byte[] ciphertext) {

    private static final byte[] MAGIC = "SAG1".getBytes(StandardCharsets.US_ASCII);
    private static final int LENGTH_FIELD_BYTES = Short.BYTES;
    private static final int MAX_KEY_LENGTH = 0xFFFF;

    public EncryptedBlob {
        if (encryptedDataKey == null || iv == null || ciphertext == null) {
            throw new CryptoException("Blob SAG1 com campo ausente");
        }
        if (iv.length != AesGcm.IV_BYTES) {
            throw new CryptoException("IV do blob SAG1 deve ter " + AesGcm.IV_BYTES + " bytes");
        }
    }

    public byte[] toBytes() {
        if (encryptedDataKey.length > MAX_KEY_LENGTH) {
            throw new CryptoException("Chave de dados cifrada grande demais para o formato SAG1");
        }
        ByteBuffer buffer = ByteBuffer.allocate(
                MAGIC.length + LENGTH_FIELD_BYTES + encryptedDataKey.length + iv.length + ciphertext.length);
        buffer.put(MAGIC);
        buffer.putShort((short) encryptedDataKey.length);
        buffer.put(encryptedDataKey);
        buffer.put(iv);
        buffer.put(ciphertext);
        return buffer.array();
    }

    public static EncryptedBlob fromBytes(byte[] bytes) {
        if (bytes == null) {
            throw new CryptoException("Blob SAG1 ausente");
        }
        int headerBytes = MAGIC.length + LENGTH_FIELD_BYTES;
        if (bytes.length < headerBytes || !Arrays.equals(Arrays.copyOf(bytes, MAGIC.length), MAGIC)) {
            throw new CryptoException("Formato desconhecido: prefixo SAG1 ausente");
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        buffer.position(MAGIC.length);
        int keyLength = Short.toUnsignedInt(buffer.getShort());
        if (buffer.remaining() < keyLength + AesGcm.IV_BYTES) {
            throw new CryptoException("Blob SAG1 truncado");
        }
        byte[] encryptedKey = new byte[keyLength];
        buffer.get(encryptedKey);
        byte[] iv = new byte[AesGcm.IV_BYTES];
        buffer.get(iv);
        byte[] ciphertext = new byte[buffer.remaining()];
        buffer.get(ciphertext);
        return new EncryptedBlob(encryptedKey, iv, ciphertext);
    }

    @Override
    public String toString() {
        return "EncryptedBlob[" + ciphertext.length + " bytes]";
    }
}
