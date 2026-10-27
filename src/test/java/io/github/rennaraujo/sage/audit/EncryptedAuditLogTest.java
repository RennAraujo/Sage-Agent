package io.github.rennaraujo.sage.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rennaraujo.sage.crypto.EncryptedBlob;
import io.github.rennaraujo.sage.crypto.EnvelopeCipher;
import io.github.rennaraujo.sage.crypto.LocalKeyProvider;
import io.github.rennaraujo.sage.storage.BlobStorage;
import io.github.rennaraujo.sage.storage.LocalBlobStorage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class EncryptedAuditLogTest {

    private static final int MASTER_KEY_BYTES = 32;
    private static final String ACTOR = "renan-ator-secreto";
    private static final String CANARY = "CANARIO-DE-CONTEUDO-777";
    private static final Instant AT = Instant.parse("2026-10-27T23:59:30Z");

    @TempDir
    Path dir;

    private EnvelopeCipher cipher;
    private BlobStorage blobs;

    @BeforeEach
    void setUp() {
        byte[] master = new byte[MASTER_KEY_BYTES];
        new SecureRandom().nextBytes(master);
        cipher = new EnvelopeCipher(new LocalKeyProvider(master));
        blobs = new LocalBlobStorage(dir);
    }

    @Test
    void auditEventStoredEncrypted() {
        new EncryptedAuditLog(cipher, blobs)
                .record(new AuditEvent(
                        AuditEventType.QUESTION_ASKED, ACTOR, AT, Map.of("route", "/api/perguntas", "n", "1")));

        List<String> keys = blobs.list("audit/");
        assertThat(keys).hasSize(1);
        assertThat(keys.get(0)).matches("audit/2026-10-27/[0-9a-f]{8}-[0-9a-f-]{27}");
        byte[] stored = blobs.get(keys.get(0)).orElseThrow();
        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain(ACTOR, "QUESTION_ASKED");
        String plain = new String(cipher.decrypt(EncryptedBlob.fromBytes(stored)), StandardCharsets.UTF_8);
        assertThat(plain).contains(ACTOR, "QUESTION_ASKED", "2026-10-27T23:59:30Z", "/api/perguntas");
    }

    @Test
    void eachEventGetsItsOwnBlob() {
        EncryptedAuditLog log = new EncryptedAuditLog(cipher, blobs);
        log.record(new AuditEvent(AuditEventType.DOCUMENT_UPLOADED, ACTOR, AT, Map.of()));
        log.record(new AuditEvent(AuditEventType.DOCUMENT_DELETED, ACTOR, AT, Map.of()));

        assertThat(blobs.list("audit/2026-10-27/")).hasSize(2);
    }

    @Test
    void failureThrowsGenericAuditExceptionAndLogsNoContent(CapturedOutput output) {
        BlobStorage failing = new BlobStorage() {
            @Override
            public void put(String key, byte[] content) {
                throw new IllegalStateException("falha de disco com " + CANARY);
            }

            @Override
            public Optional<byte[]> get(String key) {
                return Optional.empty();
            }

            @Override
            public void delete(String key) {}

            @Override
            public List<String> list(String prefix) {
                return List.of();
            }
        };
        EncryptedAuditLog log = new EncryptedAuditLog(cipher, failing);
        AuditEvent event = new AuditEvent(AuditEventType.DOCUMENT_REVIEWED, ACTOR, AT, Map.of("k", CANARY));

        assertThatThrownBy(() -> log.record(event))
                .isInstanceOf(AuditException.class)
                .hasNoCause()
                .hasMessageNotContaining(CANARY)
                .hasMessageNotContaining(ACTOR);
        assertThat(output.getAll()).contains("DOCUMENT_REVIEWED").doesNotContain(CANARY, ACTOR);
    }

    @Test
    void toStringOmitsActorAndDetails() {
        AuditEvent event = new AuditEvent(AuditEventType.IMPORT_STARTED, ACTOR, AT, Map.of("k", CANARY));

        assertThat(event.toString()).doesNotContain(ACTOR, CANARY).contains("IMPORT_STARTED");
    }

    @Test
    void eventRejectsNullDetailValues() {
        Map<String, String> withNull = new HashMap<>();
        withNull.put("a", null);

        assertThatThrownBy(() -> new AuditEvent(AuditEventType.QUESTION_ASKED, ACTOR, AT, withNull))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void eventRejectsBlankActorAndCopiesDetails() {
        assertThatThrownBy(() -> new AuditEvent(AuditEventType.QUESTION_ASKED, " ", AT, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        Map<String, String> mutable = new HashMap<>();
        mutable.put("a", "b");
        AuditEvent event = new AuditEvent(AuditEventType.QUESTION_ASKED, ACTOR, AT, mutable);
        mutable.put("c", "d");
        assertThat(event.details()).containsOnlyKeys("a");
    }
}
