package io.github.rennaraujo.sage.shared;

import java.time.Duration;
import java.util.Optional;

/**
 * Serviço temporariamente sem condição de responder (índice carregando, modelo não configurado). Vira 503; a
 * mensagem é fixa, escrita para o usuário, e vai no {@code detail}.
 */
public class ServiceUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient Optional<Duration> retryAfter;

    public ServiceUnavailableException(String message, Optional<Duration> retryAfter) {
        super(message);
        this.retryAfter = retryAfter;
    }

    public Optional<Duration> retryAfter() {
        return retryAfter;
    }
}
