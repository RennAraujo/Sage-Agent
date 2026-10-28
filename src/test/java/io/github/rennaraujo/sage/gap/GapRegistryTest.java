package io.github.rennaraujo.sage.gap;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rennaraujo.sage.crypto.EncryptedBlob;
import io.github.rennaraujo.sage.crypto.EnvelopeCipher;
import io.github.rennaraujo.sage.crypto.LocalKeyProvider;
import io.github.rennaraujo.sage.storage.BlobStorage;
import io.github.rennaraujo.sage.storage.LocalBlobStorage;
import io.github.rennaraujo.sage.testsupport.ScratchDirs;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GapRegistryTest {

    private static final int MASTER_KEY_BYTES = 32;
    private static final String CANARY = "CANARIO-FATURAMENTO-909";
    private static final String ACTOR = "renan-ator-secreto";
    private static final Instant T0 = Instant.parse("2026-10-27T12:00:00Z");

    private Path dir;

    private EnvelopeCipher cipher;
    private BlobStorage blobs;
    private final AtomicReference<Instant> now = new AtomicReference<>(T0);
    private final Clock clock = new Clock() {
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };

    @AfterEach
    void cleanUp() {
        ScratchDirs.delete(dir);
    }

    @BeforeEach
    void setUp() {
        dir = ScratchDirs.create("sage-gaps-");
        byte[] master = new byte[MASTER_KEY_BYTES];
        new SecureRandom().nextBytes(master);
        cipher = new EnvelopeCipher(new LocalKeyProvider(master));
        blobs = new LocalBlobStorage(dir);
    }

    private GapRegistry registry(int maxEntries) {
        return new GapRegistry(cipher, blobs, new GapProperties(maxEntries), clock);
    }

    @Test
    void gapStoredEncrypted() {
        registry(100).record("Como funciona o " + CANARY + "?", ACTOR);

        List<String> keys = blobs.list("gaps/");
        assertThat(keys).hasSize(1);
        assertThat(keys.get(0)).matches("gaps/[0-9a-f]{8}-[0-9a-f-]{27}");
        byte[] stored = blobs.get(keys.get(0)).orElseThrow();
        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain(CANARY, ACTOR, "2026-10-27");
        String plain = new String(cipher.decrypt(EncryptedBlob.fromBytes(stored)), StandardCharsets.UTF_8);
        assertThat(plain).contains(CANARY, "2026-10-27T12:00:00Z");
        assertThat(plain).doesNotContain(ACTOR);
    }

    @Test
    void summaryGroupsDuplicates() {
        GapRegistry registry = registry(100);
        registry.record("Como funciona o faturamento?", ACTOR);
        now.set(T0.plusSeconds(60));
        registry.record("  como FUNCIONA o  faturamento ", "outro");
        now.set(T0.plusSeconds(120));
        registry.record("Qual o prazo de cobrança?", ACTOR);
        registry.record("Como funciona o faturamento", ACTOR);

        List<GapSummary> summary = registry.summarize();

        assertThat(summary).hasSize(2);
        assertThat(summary.get(0).times()).isEqualTo(3);
        assertThat(summary.get(0).lastAskedAt()).isEqualTo(T0.plusSeconds(120));
        assertThat(summary.get(0).question()).isEqualToIgnoringCase("Como funciona o faturamento");
        assertThat(summary.get(1).times()).isEqualTo(1);
        assertThat(summary.get(1).question()).isEqualTo("Qual o prazo de cobrança?");
    }

    @Test
    void accentsAndPunctuationDoNotSplitGroups() {
        GapRegistry registry = registry(100);
        registry.record("Qual é o prazo de cobrança?", ACTOR);
        registry.record("qual e o prazo de cobranca", ACTOR);

        assertThat(registry.summarize())
                .singleElement()
                .extracting(GapSummary::times)
                .isEqualTo(2);
    }

    @Test
    void summaryReflectsGapsRecordedAfterTheFirstCall() {
        GapRegistry registry = registry(100);
        registry.record("primeira", ACTOR);
        assertThat(registry.summarize()).hasSize(1);

        registry.record("segunda", ACTOR);

        assertThat(registry.summarize()).hasSize(2);
    }

    @Test
    void newInstanceReadsWhatWasStored() {
        registry(100).record("Pergunta persistida", ACTOR);

        assertThat(registry(100).summarize()).extracting(GapSummary::question).containsExactly("Pergunta persistida");
    }

    @Test
    void oldestEntriesPrunedBeyondTheCap() {
        GapRegistry registry = registry(3);
        for (int i = 0; i < 5; i++) {
            now.set(T0.plus(Duration.ofMinutes(i)));
            registry.record("pergunta numero " + i, ACTOR);
        }

        assertThat(blobs.list("gaps/")).hasSize(3);
        assertThat(registry.summarize())
                .extracting(GapSummary::question)
                .containsExactlyInAnyOrder("pergunta numero 2", "pergunta numero 3", "pergunta numero 4");
    }

    @Test
    void actorHashIsKeyedAndStablePerInstall() {
        GapRegistry registry = registry(100);
        registry.record("primeira pergunta", ACTOR);
        registry.record("segunda pergunta", ACTOR);
        List<String> hashes = actorHashes(blobs);

        assertThat(hashes).hasSize(2).containsOnly(hashes.get(0));
        assertThat(hashes.get(0)).isNotEqualTo(plainSha256(ACTOR));

        // Outra instalação (outro armazém, outra chave secreta) dá outro hash para o mesmo ator.
        BlobStorage other = new LocalBlobStorage(dir.resolve("outra"));
        new GapRegistry(cipher, other, new GapProperties(100), clock).record("terceira", ACTOR);
        assertThat(actorHashes(other)).doesNotContain(hashes.get(0));

        // A mesma instalação, reaberta, reusa a chave guardada.
        registry(100).record("quarta pergunta", ACTOR);
        assertThat(actorHashes(blobs)).hasSize(3).containsOnly(hashes.get(0));
    }

    @Test
    void secretKeyIsStoredEncrypted() {
        registry(100).record("qualquer", ACTOR);

        assertThat(blobs.list("gaps/")).hasSize(1);
        assertThat(blobs.list("keys/")).hasSize(1);
        assertThat(blobs.list("")).hasSize(2);
    }

    @Test
    void warmLoadsEverythingOnce() {
        registry(100).record("persistida", ACTOR);
        CountingStorage counting = new CountingStorage(blobs);
        GapRegistry registry = new GapRegistry(cipher, counting, new GapProperties(100), clock);

        registry.warm();
        registry.record("nova", ACTOR);
        registry.summarize();

        assertThat(counting.lists).isEqualTo(1);
    }

    @Test
    void warmFailureIsSwallowedAndRetriedLater() {
        registry(100).record("persistida", ACTOR);
        CountingStorage flaky = new CountingStorage(blobs);
        flaky.failList = true;
        GapRegistry registry = new GapRegistry(cipher, flaky, new GapProperties(100), clock);

        registry.warm();
        flaky.failList = false;

        assertThat(registry.summarize()).extracting(GapSummary::question).containsExactly("persistida");
    }

    @Test
    void failedDeleteKeepsEntryInMemoryAndPrunesLater() {
        CountingStorage flaky = new CountingStorage(blobs);
        GapRegistry registry = new GapRegistry(cipher, flaky, new GapProperties(2), clock);
        registry.record("a primeira", ACTOR);
        now.set(T0.plusSeconds(1));
        registry.record("b segunda", ACTOR);
        now.set(T0.plusSeconds(2));
        flaky.failDelete = true;
        registry.record("c terceira", ACTOR);

        assertThat(blobs.list("gaps/")).hasSize(3);
        assertThat(registry.summarize()).hasSize(3);

        flaky.failDelete = false;
        now.set(T0.plusSeconds(3));
        registry.record("d quarta", ACTOR);

        assertThat(blobs.list("gaps/")).hasSize(2);
        assertThat(registry.summarize())
                .extracting(GapSummary::question)
                .containsExactlyInAnyOrder("c terceira", "d quarta");
    }

    private List<String> actorHashes(BlobStorage storage) {
        return storage.list("gaps/").stream()
                .map(k -> new String(
                        cipher.decrypt(EncryptedBlob.fromBytes(storage.get(k).orElseThrow())), StandardCharsets.UTF_8))
                .map(json -> json.replaceAll("(?s).*\"actorHash\":\"([0-9a-f]+)\".*", "$1"))
                .toList();
    }

    private static String plainSha256(String value) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(java.security.MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)))
                    .substring(0, 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Conta {@code list} e deixa falhar {@code list} ou {@code delete} sob demanda. */
    private static final class CountingStorage implements BlobStorage {
        private final BlobStorage delegate;
        int lists;
        boolean failList;
        boolean failDelete;

        CountingStorage(BlobStorage delegate) {
            this.delegate = delegate;
        }

        @Override
        public void put(String key, byte[] content) {
            delegate.put(key, content);
        }

        @Override
        public java.util.Optional<byte[]> get(String key) {
            return delegate.get(key);
        }

        @Override
        public void delete(String key) {
            if (failDelete) {
                throw new IllegalStateException("falha simulada");
            }
            delegate.delete(key);
        }

        @Override
        public List<String> list(String prefix) {
            lists++;
            if (failList) {
                throw new IllegalStateException("falha simulada");
            }
            return delegate.list(prefix);
        }
    }

    @Test
    void undecryptableBlobIsSkipped() {
        registry(100).record("valida", ACTOR);
        blobs.put("gaps/00000000-0000-0000-0000-000000000000", new byte[] {1, 2, 3});

        assertThat(registry(100).summarize()).extracting(GapSummary::question).containsExactly("valida");
    }
}
