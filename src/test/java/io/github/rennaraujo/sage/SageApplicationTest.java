package io.github.rennaraujo.sage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = RANDOM_PORT)
@AutoConfigureTestRestTemplate
class SageApplicationTest {

    private static final int MASTER_KEY_BYTES = 32;

    /** Chave mestra descartável, gerada a cada execução num arquivo temporário. Nunca é commitada. */
    @DynamicPropertySource
    static void localMasterKey(DynamicPropertyRegistry registry) throws IOException {
        byte[] key = new byte[MASTER_KEY_BYTES];
        new SecureRandom().nextBytes(key);
        Path file = Files.createTempFile("sage-test-master", ".key");
        file.toFile().deleteOnExit();
        Files.writeString(file, Base64.getEncoder().encodeToString(key));
        registry.add("sage.crypto.provider", () -> "local");
        registry.add("sage.crypto.local-key-file", file::toString);
    }

    @TempDir
    static Path storageDir;

    @DynamicPropertySource
    static void localStorage(DynamicPropertyRegistry registry) {
        registry.add("sage.storage.type", () -> "local");
        registry.add("sage.storage.local-dir", storageDir::toString);
    }

    @Test
    void healthIsUp(@Autowired TestRestTemplate rest) {
        assertThat(rest.getForEntity("/actuator/health", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }
}
