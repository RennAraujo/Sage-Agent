package io.github.rennaraujo.sage.shared;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Único {@code @RestControllerAdvice} do projeto: todo erro de controller vira {@code application/problem+json} com
 * tipo, título e status. Nunca vai detalhe da exceção, nome de classe, stack trace ou conteúdo para o corpo, nem
 * mensagem de exceção para o log (a mensagem pode carregar texto de documento).
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final long MIN_RETRY_AFTER_SECONDS = 1;

    @ExceptionHandler(RateLimitExceededException.class)
    ResponseEntity<ProblemDetail> rateLimited(RateLimitExceededException e) {
        long seconds = Math.max(MIN_RETRY_AFTER_SECONDS, ceilSeconds(e.retryAfter()));
        return problem(HttpStatus.TOO_MANY_REQUESTS, Long.toString(seconds));
    }

    /** Lançadas de dentro de um controller (segurança por método); no filtro quem responde são os handlers do auth. */
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> accessDenied() {
        return problem(HttpStatus.FORBIDDEN, null);
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ProblemDetail> unauthenticated() {
        return problem(HttpStatus.UNAUTHORIZED, null);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception e) {
        // Só a classe: a mensagem e a stack podem carregar conteúdo.
        LOG.error("Erro não tratado em controller: {}", e.getClass().getName());
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, null);
    }

    /** Erros padrão do Spring MVC (400, 404, 405, 415...) passam por aqui; o corpo original é descartado. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        HttpHeaders out = new HttpHeaders();
        out.putAll(headers);
        out.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        return new ResponseEntity<>(Problems.of(statusCode), out, statusCode);
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String retryAfterSeconds) {
        ResponseEntity.BodyBuilder builder =
                ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON);
        if (retryAfterSeconds != null) {
            builder.header(HttpHeaders.RETRY_AFTER, retryAfterSeconds);
        }
        return builder.body(Problems.of(status));
    }

    private static long ceilSeconds(Duration duration) {
        long seconds = duration.toSeconds();
        return duration.minusSeconds(seconds).isZero() ? seconds : seconds + 1;
    }
}
