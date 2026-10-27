package io.github.rennaraujo.sage.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Lê {@code Authorization: Bearer <chave>}, calcula o SHA-256 e compara com o hash de cada chave configurada usando
 * {@link MessageDigest#isEqual} (tempo constante). A comparação percorre todas as chaves, sem parar na primeira que
 * casa, para o tempo de resposta não revelar qual posição da lista acertou. Sem cabeçalho válido, a requisição segue
 * sem autenticação e quem responde 401 é o {@code AuthenticationEntryPoint}.
 */
final class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "bearer ";
    /** Chaves do Sage têm 45 caracteres; acima disso nem vale a pena calcular o hash. */
    private static final int MAX_TOKEN_CHARS = 256;

    private final List<ConfiguredKey> keys;

    ApiKeyAuthenticationFilter(ApiKeyProperties properties) {
        HexFormat hex = HexFormat.of();
        this.keys = properties.keys().stream()
                .map(key -> new ConfiguredKey(
                        key.name(), key.role(), hex.parseHex(key.sha256().toLowerCase(Locale.ROOT))))
                .toList();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        bearerToken(request)
                .flatMap(this::authenticate)
                .ifPresent(SecurityContextHolder.getContext()::setAuthentication);
        chain.doFilter(request, response);
    }

    private static Optional<String> bearerToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null
                || header.length() <= BEARER_PREFIX.length()
                || !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return Optional.empty();
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() || token.length() > MAX_TOKEN_CHARS ? Optional.empty() : Optional.of(token);
    }

    private Optional<UsernamePasswordAuthenticationToken> authenticate(String token) {
        byte[] hash = sha256(token);
        ConfiguredKey match = null;
        for (ConfiguredKey key : keys) {
            if (MessageDigest.isEqual(hash, key.sha256()) && match == null) {
                match = key;
            }
        }
        if (match == null) {
            return Optional.empty();
        }
        return Optional.of(UsernamePasswordAuthenticationToken.authenticated(
                match.name(),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + match.role().name()))));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível na JVM", e);
        }
    }

    private record ConfiguredKey(String name, Role role, byte[] sha256) {}
}
