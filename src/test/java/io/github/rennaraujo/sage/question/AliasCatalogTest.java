package io.github.rennaraujo.sage.question;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rennaraujo.sage.document.DocumentMetadata;
import io.github.rennaraujo.sage.document.DocumentSavedEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class AliasCatalogTest {

    @Test
    void loadsPublicLanguagesFromYaml() {
        AliasCatalog catalog = new AliasCatalog(new FakeCatalogs.Store());
        assertThat(catalog.publicLanguages())
                .extracting(AliasCatalog.PublicLanguage::id)
                .containsExactlyInAnyOrder("java", "spring-boot", "spring-framework", "spring-security");
    }

    @Test
    void internalAliasesComeFromAppNameAndFrontmatter() {
        AliasCatalog catalog = new AliasCatalog(new FakeCatalogs.Store(
                FakeCatalogs.internal("portal-x", List.of("Faturamento X")), FakeCatalogs.publicDoc("java", "25")));
        assertThat(catalog.internalAliases()).containsExactlyInAnyOrder("portal-x", "portal x", "faturamento x");
    }

    @Test
    void eventDuringRebuildIsNotLost() throws Exception {
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FakeCatalogs.Store gated = new FakeCatalogs.Store(FakeCatalogs.internal("antigo", List.of())) {
            @Override
            public List<DocumentMetadata> listMetadata() {
                List<DocumentMetadata> snapshot = super.listMetadata();
                reading.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return snapshot;
            }
        };
        AliasCatalog catalog = new AliasCatalog(gated);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Set<String>> first = pool.submit(catalog::internalAliases);
            assertThat(reading.await(5, TimeUnit.SECONDS)).isTrue();
            gated.items.add(FakeCatalogs.internal("novo", List.of()));
            catalog.onSaved(new DocumentSavedEvent(null));
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            assertThat(catalog.internalAliases()).contains("antigo", "novo");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentCallsRebuildOnlyOnce() throws Exception {
        FakeCatalogs.Store store = new FakeCatalogs.Store(FakeCatalogs.internal("portal-x", List.of()));
        AliasCatalog catalog = new AliasCatalog(store);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Set<String>>> calls = new java.util.ArrayList<>();
            for (int i = 0; i < 8; i++) {
                calls.add(pool.submit(catalog::internalAliases));
            }
            for (Future<Set<String>> call : calls) {
                call.get(5, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(store.reads.get()).isEqualTo(1);
    }

    @Test
    void failedRebuildKeepsLastGoodSnapshot() {
        FakeCatalogs.Store store = new FakeCatalogs.Store(FakeCatalogs.internal("portal-x", List.of()));
        AliasCatalog catalog = new AliasCatalog(store);
        assertThat(catalog.internalAliases()).contains("portal-x");
        store.failure = new IllegalStateException("conteudo-secreto");
        catalog.onSaved(new DocumentSavedEvent(null));
        assertThat(catalog.internalAliases()).contains("portal-x");
        assertThat(catalog.matchesInternal("portal x")).isTrue();
    }

    @Test
    void failedRebuildIsNotRetriedWithinBackoffWindow() {
        FakeCatalogs.Store store = new FakeCatalogs.Store(FakeCatalogs.internal("portal-x", List.of()));
        MutableClock clock = new MutableClock();
        AliasCatalog catalog = new AliasCatalog(store, clock);
        store.failure = new IllegalStateException("falha");
        assertThat(catalog.internalAliases()).isEmpty();
        assertThat(catalog.internalAliases()).isEmpty();
        assertThat(catalog.matchesInternal("portal-x")).isFalse();
        assertThat(store.reads.get()).isEqualTo(1);

        store.failure = null;
        clock.advance(Duration.ofSeconds(4));
        assertThat(catalog.internalAliases()).isEmpty();
        assertThat(store.reads.get()).isEqualTo(1);

        clock.advance(Duration.ofSeconds(2));
        assertThat(catalog.internalAliases()).contains("portal-x");
        assertThat(store.reads.get()).isEqualTo(2);
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-27T12:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

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
            return now;
        }
    }

    @Test
    void failedFirstLoadServesEmptyCatalogAndRetriesLater() {
        FakeCatalogs.Store store = new FakeCatalogs.Store(FakeCatalogs.internal("portal-x", List.of()));
        store.failure = new IllegalStateException("falha");
        MutableClock clock = new MutableClock();
        AliasCatalog catalog = new AliasCatalog(store, clock);
        assertThat(catalog.internalAliases()).isEmpty();
        store.failure = null;
        clock.advance(Duration.ofSeconds(6));
        assertThat(catalog.internalAliases()).contains("portal-x");
    }

    @Test
    void warmUpLoadsOnceBeforeFirstQuestion() {
        FakeCatalogs.Store store = new FakeCatalogs.Store(FakeCatalogs.internal("portal-x", List.of()));
        AliasCatalog catalog = new AliasCatalog(store);
        catalog.warmUp();
        assertThat(store.reads.get()).isEqualTo(1);
        assertThat(catalog.matchesInternal("portal x")).isTrue();
        assertThat(store.reads.get()).isEqualTo(1);
    }

    @Test
    void aliasesAreAccentInsensitive() {
        AliasCatalog catalog =
                new AliasCatalog(new FakeCatalogs.Store(FakeCatalogs.internal("cobranca", List.of("Cobrança"))));
        assertThat(catalog.internalAliases()).containsExactlyInAnyOrder("cobranca");
    }
}
