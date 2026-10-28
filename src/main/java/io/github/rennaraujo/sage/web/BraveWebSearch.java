package io.github.rennaraujo.sage.web;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Busca no Brave Search restrita às fontes oficiais. A consulta junta um {@code site:} por domínio com OR, e o
 * resultado é filtrado de novo aqui: só entra URL https cujo host é o domínio da allowlist ou subdomínio dele. O filtro
 * não depende do buscador respeitar o {@code site:}. A consulta que sai já vem sem alias interno (veja
 * {@link WebQueryBuilder}); a chave vai no cabeçalho e nunca em log. Qualquer falha vira lista vazia.
 */
public final class BraveWebSearch implements WebSearch, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(BraveWebSearch.class);
    private static final int RESULT_COUNT = 10;
    private static final int HTTP_OK = 200;
    private static final String HTTPS = "https";

    private final WebProperties properties;
    private final HttpClient client;
    private final JsonMapper json = JsonMapper.builder().build();

    public BraveWebSearch(WebProperties properties) {
        this(properties, false);
    }

    /** Em {@code allowPlainHttp} (só testes) o endpoint pode ser http, para falar com um WireMock local. */
    BraveWebSearch(WebProperties properties, boolean allowPlainHttp) {
        if (!allowPlainHttp
                && !HTTPS.equalsIgnoreCase(properties.braveEndpoint().getScheme())) {
            throw new IllegalArgumentException("sage.web.brave-endpoint deve usar https");
        }
        this.properties = properties;
        this.client = HttpClient.newBuilder()
                .connectTimeout(properties.fetchTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * Chamado pelo Spring no desligamento (o bean é {@code AutoCloseable}): fecha o cliente HTTP, que espera as buscas
     * em curso e libera as threads dele.
     */
    @Override
    public void close() {
        client.close();
    }

    /** {@code true} depois que o cliente HTTP terminou (para os testes). */
    boolean closed() {
        return client.isTerminated();
    }

    @Override
    public List<URI> search(String query, Set<String> allowedDomains, int limit) {
        if (allowedDomains.isEmpty() || limit < 1) {
            return List.of();
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(requestUri(query, allowedDomains))
                    .timeout(properties.fetchTimeout())
                    .header("X-Subscription-Token", properties.braveApiKey())
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            // O timeout do HttpRequest só cobre até os cabeçalhos; o prazo total (corpo incluído) é o do future.
            CompletableFuture<HttpResponse<byte[]>> pending = client.sendAsync(
                    request,
                    HttpResponse.BodyHandlers.limiting(
                            HttpResponse.BodyHandlers.ofByteArray(),
                            properties.maxPageBytes().toBytes()));
            HttpResponse<byte[]> response;
            try {
                response = pending.get(properties.fetchTimeout().toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                pending.cancel(true);
                LOG.warn("Busca web excedeu o prazo");
                return List.of();
            }
            if (response.statusCode() != HTTP_OK) {
                LOG.warn("Busca web recusada pelo Brave: status {}", response.statusCode());
                return List.of();
            }
            return allowedResults(json.readTree(response.body()), allowedDomains, limit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (ExecutionException | RuntimeException e) {
            // Só a classe: a mensagem poderia carregar a URL com a consulta.
            LOG.warn("Falha na busca web ({})", e.getClass().getName());
            return List.of();
        }
    }

    private URI requestUri(String query, Set<String> allowedDomains) {
        String sites =
                new TreeSet<>(allowedDomains).stream().map(d -> "site:" + d).collect(Collectors.joining(" OR "));
        String q = query + " (" + sites + ")";
        return URI.create(properties.braveEndpoint() + "?q=" + URLEncoder.encode(q, StandardCharsets.UTF_8) + "&count="
                + RESULT_COUNT);
    }

    private static List<URI> allowedResults(JsonNode root, Set<String> allowedDomains, int limit) {
        Set<URI> accepted = new LinkedHashSet<>();
        for (JsonNode result : root.path("web").path("results")) {
            parse(result.path("url").asString(""))
                    .filter(uri -> HTTPS.equals(uri.getScheme()) && AllowedHosts.matches(uri.getHost(), allowedDomains))
                    .ifPresent(accepted::add);
            if (accepted.size() >= limit) {
                break;
            }
        }
        return new ArrayList<>(accepted);
    }

    private static Optional<URI> parse(String raw) {
        try {
            return Optional.of(URI.create(raw));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
