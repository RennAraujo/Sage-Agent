package io.github.rennaraujo.sage.auth;

import io.github.rennaraujo.sage.shared.Problems;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Respostas 401 e 403 geradas dentro da cadeia de filtros (antes de qualquer controller), no mesmo formato
 * {@code application/problem+json} do {@code ApiExceptionHandler}.
 */
final class ProblemJsonHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private static final String CHALLENGE = "Bearer realm=\"sage\"";

    private final JacksonJsonHttpMessageConverter converter = new JacksonJsonHttpMessageConverter();

    @Override
    public void commence(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, CHALLENGE);
        write(response, HttpStatus.UNAUTHORIZED);
    }

    @Override
    public void handle(
            HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException)
            throws IOException {
        write(response, HttpStatus.FORBIDDEN);
    }

    private void write(HttpServletResponse response, HttpStatus status) throws IOException {
        response.setStatus(status.value());
        converter.write(
                Problems.of(status), MediaType.APPLICATION_PROBLEM_JSON, new ServletServerHttpResponse(response));
    }
}
