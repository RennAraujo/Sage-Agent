package io.github.rennaraujo.sage.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EnvelopeCipherTest {

    private static final String CANARY = "SEGREDO-CANARIO-123";
    private static final int MASTER_KEY_BYTES = 32;
    private static final int MAGIC_BYTES = 4;
    private static final int WRAPPED_KEY_OFFSET = MAGIC_BYTES + 2;

    private EnvelopeCipher cipher;

    @BeforeEach
    void setUp() {
        byte[] master = new byte[MASTER_KEY_BYTES];
        new SecureRandom().nextBytes(master);
        cipher = new EnvelopeCipher(new LocalKeyProvider(master));
    }

    @Test
    void roundTripReturnsOriginal() {
        byte[] original = CANARY.getBytes(StandardCharsets.UTF_8);

        assertThat(cipher.decrypt(cipher.encrypt(original))).isEqualTo(original);
    }

    @Test
    void ciphertextDoesNotContainPlaintext() {
        byte[] original = CANARY.getBytes(StandardCharsets.UTF_8);

        String serialized = new String(cipher.encrypt(original).toBytes(), StandardCharsets.ISO_8859_1);

        assertThat(serialized).doesNotContain(CANARY);
    }

    @Test
    void eachEncryptionUsesFreshKeyAndIv() {
        byte[] original = CANARY.getBytes(StandardCharsets.UTF_8);
        RecordingKeyProvider provider = new RecordingKeyProvider();
        EnvelopeCipher recording = new EnvelopeCipher(provider);

        EncryptedBlob first = recording.encrypt(original);
        EncryptedBlob second = recording.encrypt(original);

        assertThat(first.iv()).isNotEqualTo(second.iv());
        assertThat(first.encryptedDataKey()).isNotEqualTo(second.encryptedDataKey());
        assertThat(provider.generatedPlaintextKeys).hasSize(2);
        assertThat(provider.generatedPlaintextKeys.get(0)).isNotEqualTo(provider.generatedPlaintextKeys.get(1));
    }

    @Test
    void tamperedIvFails() {
        byte[] bytes = cipher.encrypt(CANARY.getBytes(StandardCharsets.UTF_8)).toBytes();
        bytes[ivOffset(bytes)] ^= 1;

        assertThatThrownBy(() -> cipher.decrypt(EncryptedBlob.fromBytes(bytes))).isInstanceOf(CryptoException.class);
    }

    @Test
    void tamperedWrappedDataKeyFails() {
        byte[] bytes = cipher.encrypt(CANARY.getBytes(StandardCharsets.UTF_8)).toBytes();
        bytes[WRAPPED_KEY_OFFSET] ^= 1;

        assertThatThrownBy(() -> cipher.decrypt(EncryptedBlob.fromBytes(bytes))).isInstanceOf(CryptoException.class);
    }

    @Test
    void nullBytesAreRejectedAsCryptoException() {
        assertThatThrownBy(() -> EncryptedBlob.fromBytes(null)).isInstanceOf(CryptoException.class);
    }

    @Test
    void blobRejectsWrongIvLength() {
        assertThatThrownBy(() -> new EncryptedBlob(new byte[1], new byte[5], new byte[1]))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    void blobRejectsNullFields() {
        assertThatThrownBy(() -> new EncryptedBlob(null, new byte[12], new byte[1]))
                .isInstanceOf(CryptoException.class);
    }

    private static int ivOffset(byte[] blob) {
        int keyLength = ((blob[MAGIC_BYTES] & 0xFF) << 8) | (blob[MAGIC_BYTES + 1] & 0xFF);
        return WRAPPED_KEY_OFFSET + keyLength;
    }

    @Test
    void tamperedCiphertextFails() {
        EncryptedBlob blob = cipher.encrypt(CANARY.getBytes(StandardCharsets.UTF_8));
        byte[] tampered = blob.ciphertext().clone();
        tampered[0] ^= 1;
        EncryptedBlob forged = new EncryptedBlob(blob.encryptedDataKey(), blob.iv(), tampered);

        assertThatThrownBy(() -> cipher.decrypt(forged)).isInstanceOf(CryptoException.class);
    }

    @Test
    void serializationRoundTrip() {
        byte[] original = CANARY.getBytes(StandardCharsets.UTF_8);

        EncryptedBlob restored =
                EncryptedBlob.fromBytes(cipher.encrypt(original).toBytes());

        assertThat(cipher.decrypt(restored)).isEqualTo(original);
    }

    @Test
    void rejectsUnknownMagic() {
        byte[] bytes = cipher.encrypt(CANARY.getBytes(StandardCharsets.UTF_8)).toBytes();
        byte[] wrong = Arrays.copyOf(bytes, bytes.length);
        wrong[0] = 'X';

        assertThatThrownBy(() -> EncryptedBlob.fromBytes(wrong)).isInstanceOf(CryptoException.class);
    }

    @Test
    void rejectsTruncatedBlob() {
        byte[] bytes = cipher.encrypt(CANARY.getBytes(StandardCharsets.UTF_8)).toBytes();
        byte[] truncated = Arrays.copyOf(bytes, 10);

        assertThatThrownBy(() -> EncryptedBlob.fromBytes(truncated)).isInstanceOf(CryptoException.class);
    }

    @Test
    void zeroesPlaintextDataKeyAfterUse() {
        RecordingKeyProvider provider = new RecordingKeyProvider();
        EnvelopeCipher recording = new EnvelopeCipher(provider);

        recording.decrypt(recording.encrypt(CANARY.getBytes(StandardCharsets.UTF_8)));

        assertThat(provider.issuedKeys)
                .isNotEmpty()
                .allSatisfy(key -> assertThat(key).containsOnly((byte) 0));
    }

    private static final class RecordingKeyProvider implements KeyProvider {

        private final LocalKeyProvider delegate = new LocalKeyProvider(new byte[MASTER_KEY_BYTES]);
        private final List<byte[]> issuedKeys = new ArrayList<>();
        private final List<byte[]> generatedPlaintextKeys = new ArrayList<>();

        @Override
        public DataKey generateDataKey() {
            DataKey key = delegate.generateDataKey();
            issuedKeys.add(key.plaintext());
            generatedPlaintextKeys.add(key.plaintext().clone());
            return key;
        }

        @Override
        public byte[] decryptDataKey(byte[] encryptedKey) {
            byte[] key = delegate.decryptDataKey(encryptedKey);
            issuedKeys.add(key);
            return key;
        }
    }
}
