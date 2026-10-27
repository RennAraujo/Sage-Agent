package io.github.rennaraujo.sage.question.guard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TimeLimitedGroundednessCheckerTest {

    private static final Duration LIMIT = Duration.ofMillis(100);

    @Test
    void fastSupportedAnswerPassesThrough() {
        var checker = new TimeLimitedGroundednessChecker((s, e) -> true, LIMIT);

        assertThat(checker.isSupported("frase", List.of("trecho"))).isTrue();
    }

    @Test
    void fastUnsupportedAnswerPassesThrough() {
        var checker = new TimeLimitedGroundednessChecker((s, e) -> false, LIMIT);

        assertThat(checker.isSupported("frase", List.of("trecho"))).isFalse();
    }

    @Test
    void slowCheckerIsTreatedAsUnsupported() {
        var checker = new TimeLimitedGroundednessChecker(
                (s, e) -> {
                    sleep(2000);
                    return true;
                },
                LIMIT);

        long start = System.nanoTime();
        boolean supported = checker.isSupported("frase", List.of("trecho"));
        long millis = (System.nanoTime() - start) / 1_000_000;

        assertThat(supported).isFalse();
        assertThat(millis).isLessThan(1900);
        assertThat(checker.timeouts()).isEqualTo(1);
    }

    @Test
    void failingCheckerIsTreatedAsUnsupported() {
        var checker = new TimeLimitedGroundednessChecker(
                (s, e) -> {
                    throw new IllegalStateException("falhou");
                },
                LIMIT);

        assertThat(checker.isSupported("frase", List.of("trecho"))).isFalse();
    }

    @Test
    void saturatedCheckerFailsClosedWithoutStartingNewRuns() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();
        var checker = new TimeLimitedGroundednessChecker(
                (s, e) -> {
                    runs.incrementAndGet();
                    awaitUninterruptibly(release);
                    return true;
                },
                LIMIT,
                1);
        try {
            // A primeira estoura o prazo e fica órfã segurando a única permissão.
            assertThat(checker.isSupported("a", List.of("t"))).isFalse();
            assertThat(checker.timeouts()).isEqualTo(1);

            assertThat(checker.isSupported("b", List.of("t"))).isFalse();
            assertThat(checker.isSupported("c", List.of("t"))).isFalse();

            assertThat(runs).hasValue(1);
            assertThat(checker.saturated()).isEqualTo(2);
        } finally {
            release.countDown();
            checker.close();
        }
    }

    @Test
    void permitIsReturnedWhenTheOrphanFinishes() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();
        var checker = new TimeLimitedGroundednessChecker(
                (s, e) -> {
                    if (runs.incrementAndGet() == 1) {
                        awaitUninterruptibly(release);
                    }
                    return true;
                },
                LIMIT,
                1);
        try {
            assertThat(checker.isSupported("a", List.of("t"))).isFalse();
            release.countDown();
            Thread.sleep(200);

            assertThat(checker.isSupported("b", List.of("t"))).isTrue();
        } finally {
            checker.close();
        }
    }

    @Test
    void waitingForAPermitPastTheDeadlineNeverStartsALateRun() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();
        var checker = new TimeLimitedGroundednessChecker(
                (s, e) -> {
                    runs.incrementAndGet();
                    awaitUninterruptibly(release);
                    return true;
                },
                LIMIT,
                1);
        try {
            checker.isSupported("a", List.of("t"));
            checker.isSupported("b", List.of("t"));
            release.countDown();
            Thread.sleep(300);

            assertThat(runs).hasValue(1);
        } finally {
            checker.close();
        }
    }

    @Test
    void closeWaitsForRunningInferencesAndThenStopsAcceptingWork() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean finished = new AtomicBoolean();
        var checker = new TimeLimitedGroundednessChecker(
                (s, e) -> {
                    started.countDown();
                    sleep(400);
                    finished.set(true);
                    return true;
                },
                LIMIT,
                1);
        checker.isSupported("a", List.of("t"));
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

        checker.close();

        assertThat(finished).isTrue();
        assertThat(checker.isSupported("b", List.of("t"))).isFalse();
    }

    @Test
    void nonPositiveLimitOrConcurrencyIsRejected() {
        assertThatThrownBy(() -> new TimeLimitedGroundednessChecker((s, e) -> true, Duration.ZERO, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TimeLimitedGroundednessChecker((s, e) -> true, LIMIT, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
