package io.github.rennaraujo.sage.question.guard;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Põe um prazo por frase e um teto de concorrência em volta de outro {@link GroundednessChecker}. Passou do prazo, não
 * coube no teto ou o verificador falhou, a frase é tratada como não sustentada (falha fechado): o guard a descarta e
 * conta, em vez de segurar o stream ou deixar passar o que não foi verificado. Nos logs vão só números (nada de texto
 * da frase nem dos trechos); o tempo de cada frase sai em DEBUG.
 *
 * <p>O prazo não interrompe a inferência: o ONNX Runtime não é cancelável no meio de uma chamada, então uma
 * verificação que estourou continua rodando até o fim em segundo plano, com o resultado ignorado. Para essas
 * execuções órfãs não se acumularem (memória, CPU), cada execução segura uma permissão de um {@link Semaphore} de
 * {@code maxConcurrency} permissões, adquirida dentro da tarefa e devolvida só quando a inferência termina de verdade.
 * Sem permissão dentro do prazo, a frase é descartada sem iniciar uma execução nova ("saturado" no log). O {@link
 * #close()} para de aceitar trabalho e espera, por tempo limitado, as execuções em curso terminarem, para a sessão ONNX
 * não ser fechada debaixo delas.
 */
public final class TimeLimitedGroundednessChecker implements GroundednessChecker, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(TimeLimitedGroundednessChecker.class);
    private static final int PERMIT_WAIT_SHARE_PERCENT = 75;
    private static final Duration DRAIN_LIMIT = Duration.ofSeconds(10);

    private enum Outcome {
        SUPPORTED,
        UNSUPPORTED,
        SATURATED
    }

    private final GroundednessChecker delegate;
    private final Duration limit;
    private final int maxConcurrency;
    private final Semaphore permits;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong timeouts = new AtomicLong();
    private final AtomicLong saturated = new AtomicLong();

    /** Com a concorrência padrão: metade dos núcleos, ao menos 1. */
    public TimeLimitedGroundednessChecker(GroundednessChecker delegate, Duration limit) {
        this(delegate, limit, GuardProperties.defaultConcurrency());
    }

    public TimeLimitedGroundednessChecker(GroundednessChecker delegate, Duration limit, int maxConcurrency) {
        if (limit.isZero() || limit.isNegative()) {
            throw new IllegalArgumentException("O prazo por frase precisa ser positivo");
        }
        if (maxConcurrency < 1) {
            throw new IllegalArgumentException("A concorrência máxima precisa ser ao menos 1");
        }
        this.delegate = delegate;
        this.limit = limit;
        this.maxConcurrency = maxConcurrency;
        this.permits = new Semaphore(maxConcurrency);
    }

    @Override
    public boolean isSupported(String sentence, List<String> evidence) {
        if (closed.get()) {
            return false;
        }
        long start = System.nanoTime();
        // A espera pela permissão acaba antes do prazo do chamador: assim "saturado" não vira "estourou o prazo".
        long deadline = start + limit.toNanos() * PERMIT_WAIT_SHARE_PERCENT / 100;
        AtomicBoolean abandoned = new AtomicBoolean();
        Future<Outcome> future;
        try {
            future = executor.submit(() -> run(sentence, evidence, deadline, abandoned));
        } catch (RuntimeException e) {
            return false;
        }
        try {
            Outcome outcome = future.get(limit.toNanos(), TimeUnit.NANOSECONDS);
            if (outcome == Outcome.SATURATED) {
                saturated.incrementAndGet();
                LOG.warn(
                        "Fundamentação saturada e a frase foi descartada: trechos={} concorrenciaMax={}",
                        evidence.size(),
                        maxConcurrency);
                return false;
            }
            boolean supported = outcome == Outcome.SUPPORTED;
            LOG.debug(
                    "Fundamentação verificada: trechos={} chars={} suportada={} tempoMs={}",
                    evidence.size(),
                    sentence.length(),
                    supported,
                    millisSince(start));
            return supported;
        } catch (TimeoutException e) {
            abandoned.set(true);
            future.cancel(false);
            timeouts.incrementAndGet();
            LOG.warn(
                    "Fundamentação estourou o prazo e a frase foi descartada: trechos={} prazoMs={}",
                    evidence.size(),
                    limit.toMillis());
            return false;
        } catch (ExecutionException e) {
            LOG.warn(
                    "Fundamentação falhou e a frase foi descartada: tipo={} tempoMs={}",
                    e.getCause().getClass().getSimpleName(),
                    millisSince(start));
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            abandoned.set(true);
            future.cancel(false);
            return false;
        }
    }

    /** Dentro da tarefa: pega a permissão (dentro do prazo), roda a inferência e devolve a permissão no fim dela. */
    private Outcome run(String sentence, List<String> evidence, long deadlineNanos, AtomicBoolean abandoned)
            throws InterruptedException {
        long wait = Math.max(0, deadlineNanos - System.nanoTime());
        if (!permits.tryAcquire(wait, TimeUnit.NANOSECONDS)) {
            return Outcome.SATURATED;
        }
        try {
            if (abandoned.get()) {
                // O chamador já desistiu enquanto esperava a permissão: não inicia uma inferência que ninguém lerá.
                return Outcome.UNSUPPORTED;
            }
            return delegate.isSupported(sentence, evidence) ? Outcome.SUPPORTED : Outcome.UNSUPPORTED;
        } finally {
            permits.release();
        }
    }

    /** Quantas verificações estouraram o prazo desde que o objeto foi criado. */
    public long timeouts() {
        return timeouts.get();
    }

    /** Quantas frases foram descartadas por falta de permissão (verificador saturado). */
    public long saturated() {
        return saturated.get();
    }

    /** Para de aceitar trabalho e espera, por tempo limitado, as inferências em curso (inclusive as órfãs) acabarem. */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        executor.shutdown();
        try {
            if (!permits.tryAcquire(maxConcurrency, DRAIN_LIMIT.toNanos(), TimeUnit.NANOSECONDS)) {
                LOG.warn("Fundamentação ainda em curso ao desligar; seguindo após {} s", DRAIN_LIMIT.toSeconds());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
