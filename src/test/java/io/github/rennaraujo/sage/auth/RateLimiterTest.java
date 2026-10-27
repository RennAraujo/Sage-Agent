package io.github.rennaraujo.sage.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.bucket4j.TimeMeter;
import io.github.rennaraujo.sage.shared.RateLimitExceededException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RateLimiterTest {

    private static final int QUESTIONS = 2;
    private static final int UPLOADS = 1;
    private static final int DEFAULT_UPLOADS_PER_MINUTE = 10;
    private static final int DEFAULT_QUESTIONS_PER_MINUTE = 20;
    private static final long ELAPSED_SECONDS = 10;

    private final AtomicLong nanos = new AtomicLong();
    private RateLimiter limiter;

    @BeforeEach
    void setUp() {
        TimeMeter meter = new TimeMeter() {
            @Override
            public long currentTimeNanos() {
                return nanos.get();
            }

            @Override
            public boolean isWallClockBased() {
                return false;
            }
        };
        limiter = new RateLimiter(new RateLimitProperties(QUESTIONS, UPLOADS), meter);
    }

    @Test
    void allowsUpToTheLimitThenBlocks() {
        assertThat(limiter.tryConsume("ana", RateLimitedAction.QUESTION)).isTrue();
        assertThat(limiter.tryConsume("ana", RateLimitedAction.QUESTION)).isTrue();
        assertThat(limiter.tryConsume("ana", RateLimitedAction.QUESTION)).isFalse();
    }

    @Test
    void bucketsAreIndependentPerKeyAndPerAction() {
        limiter.tryConsume("ana", RateLimitedAction.UPLOAD);

        assertThat(limiter.tryConsume("ana", RateLimitedAction.UPLOAD)).isFalse();
        assertThat(limiter.tryConsume("ana", RateLimitedAction.QUESTION)).isTrue();
        assertThat(limiter.tryConsume("beto", RateLimitedAction.UPLOAD)).isTrue();
    }

    @Test
    void retryAfterIsZeroWhenAvailableAndShrinksAsTimePasses() {
        assertThat(limiter.retryAfter("ana", RateLimitedAction.QUESTION)).isEqualTo(Duration.ZERO);
        limiter.tryConsume("ana", RateLimitedAction.QUESTION);
        limiter.tryConsume("ana", RateLimitedAction.QUESTION);

        Duration initial = limiter.retryAfter("ana", RateLimitedAction.QUESTION);
        nanos.addAndGet(Duration.ofSeconds(ELAPSED_SECONDS).toNanos());
        Duration later = limiter.retryAfter("ana", RateLimitedAction.QUESTION);

        assertThat(initial).isPositive().isLessThanOrEqualTo(Duration.ofMinutes(1));
        assertThat(later).isLessThan(initial);
    }

    @Test
    void refillsAfterOneMinute() {
        limiter.tryConsume("ana", RateLimitedAction.UPLOAD);
        assertThat(limiter.tryConsume("ana", RateLimitedAction.UPLOAD)).isFalse();

        nanos.addAndGet(Duration.ofMinutes(1).toNanos());

        assertThat(limiter.tryConsume("ana", RateLimitedAction.UPLOAD)).isTrue();
    }

    @Test
    void requireThrowsWithRetryAfterWhenExhausted() {
        limiter.require("ana", RateLimitedAction.UPLOAD);

        assertThatThrownBy(() -> limiter.require("ana", RateLimitedAction.UPLOAD))
                .isInstanceOfSatisfying(
                        RateLimitExceededException.class,
                        e -> assertThat(e.retryAfter()).isPositive().isLessThanOrEqualTo(Duration.ofMinutes(1)));
        assertThatCode(() -> limiter.require("beto", RateLimitedAction.UPLOAD)).doesNotThrowAnyException();
    }

    @Test
    void defaultsAre20QuestionsAnd10UploadsPerMinute() {
        RateLimiter real =
                new RateLimiter(new RateLimitProperties(DEFAULT_QUESTIONS_PER_MINUTE, DEFAULT_UPLOADS_PER_MINUTE));
        int allowed = 0;
        while (real.tryConsume("ana", RateLimitedAction.UPLOAD)) {
            allowed++;
        }
        assertThat(allowed).isEqualTo(DEFAULT_UPLOADS_PER_MINUTE);
    }
}
