package io.github.rennaraujo.sage.shared;

import java.time.Duration;

/** Limite de requisições estourado. O {@link ApiExceptionHandler} converte em 429 com {@code Retry-After}. */
public class RateLimitExceededException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient Duration retryAfter;

    public RateLimitExceededException(Duration retryAfter) {
        super("Limite de requisições excedido");
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
