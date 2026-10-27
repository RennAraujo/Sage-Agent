package io.github.rennaraujo.sage.shared;

import io.github.rennaraujo.sage.document.InvalidDocumentException;
import io.github.rennaraujo.sage.upload.DocumentConflictException;
import io.github.rennaraujo.sage.upload.DocumentNotFoundException;
import io.github.rennaraujo.sage.upload.SecretFinding;
import io.github.rennaraujo.sage.upload.SecretsFoundException;
import io.github.rennaraujo.sage.upload.UnsupportedDocumentException;
import io.github.rennaraujo.sage.upload.UploadTooLargeException;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /** As mensagens destas exceções são fixas e nunca citam o conteúdo recebido, então podem ir no {@code detail}. */
    @ExceptionHandler(UnsupportedDocumentException.class)
    ResponseEntity<ProblemDetail> unsupportedDocument(UnsupportedDocumentException e) {
        return problemWithDetail(HttpStatus.UNSUPPORTED_MEDIA_TYPE, e.getMessage());
    }

    @ExceptionHandler(UploadTooLargeException.class)
    ResponseEntity<ProblemDetail> tooLarge(UploadTooLargeException e) {
        return problemWithDetail(HttpStatus.CONTENT_TOO_LARGE, e.getMessage());
    }

    @ExceptionHandler(InvalidDocumentException.class)
    ResponseEntity<ProblemDetail> invalidDocument(InvalidDocumentException e) {
        return problemWithDetail(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
    }

    @ExceptionHandler(DocumentConflictException.class)
    ResponseEntity<ProblemDetail> documentConflict(DocumentConflictException e) {
        return problemWithDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(DocumentNotFoundException.class)
    ResponseEntity<ProblemDetail> documentNotFound(DocumentNotFoundException e) {
        return problemWithDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    /** Só tipo e linha de cada achado (e o caminho da entrada, no caso de zip); nunca o valor encontrado. */
    @ExceptionHandler(SecretsFoundException.class)
    ResponseEntity<ProblemDetail> secretsFound(SecretsFoundException e) {
        ProblemDetail body = Problems.of(HttpStatus.UNPROCESSABLE_CONTENT);
        body.setType(URI.create("urn:sage:problem:secret-detected"));
        body.setTitle("Segredo detectado no documento");
        body.setDetail("O documento parece conter credenciais; remova-as e envie de novo");
        List<Map<String, Object>> secrets =
                e.findings().stream().map(ApiExceptionHandler::describe).toList();
        body.setProperty("segredos", secrets);
        e.file().ifPresent(file -> body.setProperty("arquivo", file));
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body);
    }

    private static Map<String, Object> describe(SecretFinding finding) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("tipo", finding.type());
        entry.put("linha", finding.line());
        return entry;
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

    private static ResponseEntity<ProblemDetail> problemWithDetail(HttpStatus status, String detail) {
        ProblemDetail body = Problems.of(status);
        body.setDetail(detail);
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body);
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
