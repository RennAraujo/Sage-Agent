package io.github.rennaraujo.sage.question.guard;

import io.github.rennaraujo.sage.upload.SecretScanner;
import java.util.List;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * Cria um {@link AnswerGuard} por chamada ao modelo, já com o verificador de fundamentação (com prazo por frase e teto
 * de concorrência) e o scanner de segredos. Fica separado do guard porque o guard é descartável (um por resposta) e
 * estes são compartilhados. Também é dono do {@link #scheduler()} em que o guard roda: a verificação de fundamentação
 * leva centenas de milissegundos e não pode bloquear a thread que entrega o stream do modelo.
 *
 * <p>No desligamento, o {@link #close()} fecha o verificador com prazo (que espera as inferências em curso) e o
 * scheduler; só depois o Spring fecha o verificador de baixo e sua sessão ONNX, porque este bean depende dele.
 */
public final class AnswerGuardFactory implements AutoCloseable {

    private static final int SCHEDULER_QUEUE = 10_000;
    private static final int SCHEDULER_TTL_SECONDS = 60;

    private final GroundednessChecker checker;
    private final TimeLimitedGroundednessChecker limited;
    private final SecretScanner scanner;
    private final Scheduler scheduler;
    private final boolean ownsScheduler;

    public AnswerGuardFactory(GroundednessChecker checker, SecretScanner scanner, GuardProperties properties) {
        this(checker, scanner, properties, null);
    }

    /** Com {@code shared} nulo, cria e é dono do próprio scheduler; senão usa {@code shared} e não o fecha. */
    private AnswerGuardFactory(
            GroundednessChecker checker, SecretScanner scanner, GuardProperties properties, Scheduler shared) {
        // O pass-through não faz inferência: não precisa de prazo nem de thread.
        if (checker instanceof PassThroughGroundednessChecker) {
            this.limited = null;
            this.checker = checker;
        } else {
            this.limited = new TimeLimitedGroundednessChecker(
                    checker, properties.nliTimeoutPerSentence(), properties.nliMaxConcurrency());
            this.checker = limited;
        }
        this.scanner = scanner;
        // Uma thread por resposta em andamento que esteja verificando; o teto segue o da concorrência do NLI.
        int threads = Math.max(4, properties.nliMaxConcurrency() * 2);
        this.ownsScheduler = shared == null;
        this.scheduler = shared != null
                ? shared
                : Schedulers.newBoundedElastic(threads, SCHEDULER_QUEUE, "sage-guard", SCHEDULER_TTL_SECONDS, true);
    }

    /**
     * Sem modelo de NLI: só citações e segredos. Usado quando não há verificador configurado (testes e os construtores
     * de conveniência do {@code AnswerService}). Sem inferência, o guard não precisa de scheduler próprio: usa o
     * {@link Schedulers#boundedElastic()} compartilhado do Reactor, que ninguém precisa fechar. Antes, cada chamada
     * criava um scheduler novo que nunca era fechado.
     */
    public static AnswerGuardFactory citationsOnly() {
        return new AnswerGuardFactory(
                new PassThroughGroundednessChecker(),
                new SecretScanner(),
                new GuardProperties(0.5, 256, false),
                Schedulers.boundedElastic());
    }

    /**
     * @param evidence o texto de cada trecho do contexto, na ordem das citações ({@code [1]} é o índice 0)
     */
    public AnswerGuard create(List<String> evidence, boolean comparison, boolean flow) {
        return new AnswerGuard(evidence, comparison, flow, checker, scanner);
    }

    /**
     * @param evidence o texto de cada trecho do contexto, na ordem das citações ({@code [1]} é o índice 0)
     * @param sources a linguagem e a versão de cada trecho como o código as escreve ("Java 21"), vazio para trecho
     *     interno ou sem versão; o molde de ausência da comparação só passa nomeando a versão dos trechos que cita
     */
    public AnswerGuard create(List<String> evidence, List<String> sources, boolean comparison, boolean flow) {
        return new AnswerGuard(evidence, sources, comparison, flow, checker, scanner);
    }

    /** Onde o guard deve rodar durante o stream (fora da thread do SDK do modelo). */
    public Scheduler scheduler() {
        return scheduler;
    }

    /** Verificações que estouraram o prazo por frase desde a partida; 0 sem modelo de NLI. */
    public long nliTimeouts() {
        return limited == null ? 0 : limited.timeouts();
    }

    /** Frases descartadas porque o verificador estava saturado; 0 sem modelo de NLI. */
    public long nliSaturated() {
        return limited == null ? 0 : limited.saturated();
    }

    @Override
    public void close() {
        if (ownsScheduler) {
            scheduler.dispose();
        }
        if (limited != null) {
            limited.close();
        }
    }
}
