package io.github.rennaraujo.sage.question;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.benmanes.caffeine.cache.Ticker;
import io.github.rennaraujo.sage.document.Source;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class AnswerCacheTest {

    private final AtomicLong nanos = new AtomicLong();
    private final Ticker ticker = nanos::get;

    @Test
    void normalizationLowercasesTrimsCollapsesAndDropsTrailingPunctuation() {
        assertThat(AnswerCache.normalize("  Como   USAR\ttoList no Java 21 ?!  "))
                .isEqualTo("como usar tolist no java 21");
        assertThat(AnswerCache.normalize("Como usar toList?")).isEqualTo(AnswerCache.normalize("como usar tolist"));
    }

    @Test
    void getReturnsWhatWasPut() {
        AnswerCache cache = cache(10, Duration.ofHours(6));
        cache.put(key("a"), answer("x"));

        assertThat(cache.get(key("a"))).map(CachedAnswer::sentences).contains(List.of("x"));
        assertThat(cache.get(key("b"))).isEmpty();
    }

    @Test
    void keyDistinguishesLanguageVersionsAndType() {
        AnswerCache cache = cache(10, Duration.ofHours(6));
        cache.put(key("a"), answer("x"));

        CacheKey otherVersion =
                new CacheKey("a", Set.of(Source.PUBLIC), Optional.of("java"), List.of("17"), QuestionType.LOOKUP);
        CacheKey otherType =
                new CacheKey("a", Set.of(Source.PUBLIC), Optional.of("java"), List.of("21"), QuestionType.COMPARISON);
        assertThat(cache.get(otherVersion)).isEmpty();
        assertThat(cache.get(otherType)).isEmpty();
    }

    @Test
    void clearRemovesEverything() {
        AnswerCache cache = cache(10, Duration.ofHours(6));
        cache.put(key("a"), answer("x"));
        cache.clear();

        assertThat(cache.get(key("a"))).isEmpty();
    }

    @Test
    void putStartedBeforeClearIsDiscarded() {
        AnswerCache cache = cache(10, Duration.ofHours(6));
        long epoch = cache.epoch();
        cache.clear();
        cache.put(key("a"), answer("stale"), epoch);

        assertThat(cache.get(key("a"))).isEmpty();
        cache.put(key("a"), answer("fresh"), cache.epoch());
        assertThat(cache.get(key("a"))).isPresent();
    }

    @Test
    void entriesExpireAfterTtl() {
        AnswerCache cache = cache(10, Duration.ofHours(6));
        cache.put(key("a"), answer("x"));
        nanos.addAndGet(Duration.ofHours(7).toNanos());

        assertThat(cache.get(key("a"))).isEmpty();
    }

    @Test
    void sizeIsBounded() {
        AnswerCache cache = cache(2, Duration.ofHours(6));
        for (int i = 0; i < 20; i++) {
            cache.put(key("q" + i), answer("x"));
        }
        cache.cleanUp();

        assertThat(cache.size()).isLessThanOrEqualTo(2);
    }

    @Test
    void cacheWritesNothingToStorage() {
        for (Class<?> type : List.of(AnswerCache.class, CachedAnswer.class, CacheKey.class, ConversationStore.class)) {
            assertThat(Serializable.class.isAssignableFrom(type))
                    .as(type.getSimpleName())
                    .isFalse();
            for (Field field : type.getDeclaredFields()) {
                assertThat(field.getType().getName())
                        .as(type.getSimpleName() + "." + field.getName())
                        .doesNotContain("BlobStorage")
                        .doesNotContain("java.io.File")
                        .isNotEqualTo(Path.class.getName());
            }
        }
    }

    private AnswerCache cache(int max, Duration ttl) {
        return new AnswerCache(new CacheProperties(ttl, max), ticker);
    }

    private static CacheKey key(String question) {
        return new CacheKey(question, Set.of(Source.PUBLIC), Optional.of("java"), List.of("21"), QuestionType.LOOKUP);
    }

    private static CachedAnswer answer(String sentence) {
        return new CachedAnswer(
                List.of(sentence),
                new AnswerMetadata(
                        "c",
                        Route.FAST_MODEL,
                        Optional.empty(),
                        0.9,
                        List.of(),
                        Optional.empty(),
                        false,
                        0,
                        Map.of(),
                        List.of()));
    }
}
