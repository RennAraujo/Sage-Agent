package io.github.rennaraujo.sage.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rennaraujo.sage.testsupport.ScratchDirs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class LocalKeyProviderTest {

    private static final int KEY_BYTES = 32;

    private final Path dir = ScratchDirs.create("sage-teste-");

    @AfterEach
    void deleteScratchDir() {
        ScratchDirs.delete(dir);
    }

    @Test
    void rejectsKeyThatIsNot32Bytes() throws IOException {
        Path file = writeKey(new byte[16]);

        assertThatThrownBy(() -> LocalKeyProvider.fromFile(file))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    void rejectsMissingFile() {
        Path missing = dir.resolve("nao-existe.key");

        assertThatThrownBy(() -> LocalKeyProvider.fromFile(missing))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("generate-local-key");
    }

    @Test
    void rejectsFileThatIsNotBase64() throws IOException {
        Path file = dir.resolve("lixo.key");
        Files.write(file, "isto nao e base64 !!!".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> LocalKeyProvider.fromFile(file))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("base64");
    }

    @Test
    void dataKeyRoundTrip() throws IOException {
        byte[] master = new byte[KEY_BYTES];
        new SecureRandom().nextBytes(master);
        LocalKeyProvider provider = LocalKeyProvider.fromFile(writeKey(master));

        DataKey dataKey = provider.generateDataKey();

        assertThat(dataKey.plaintext()).hasSize(KEY_BYTES);
        assertThat(provider.decryptDataKey(dataKey.encrypted())).isEqualTo(dataKey.plaintext());
    }

    @Test
    void decryptWithOtherMasterKeyFails() {
        LocalKeyProvider one = new LocalKeyProvider(new byte[KEY_BYTES]);
        byte[] other = new byte[KEY_BYTES];
        other[0] = 1;
        LocalKeyProvider two = new LocalKeyProvider(other);
        byte[] wrapped = one.generateDataKey().encrypted();

        assertThatThrownBy(() -> two.decryptDataKey(wrapped)).isInstanceOf(CryptoException.class);
    }

    private Path writeKey(byte[] bytes) throws IOException {
        Path file = dir.resolve("master.key");
        Files.write(file, Base64.getEncoder().encode(bytes));
        return file;
    }
}
