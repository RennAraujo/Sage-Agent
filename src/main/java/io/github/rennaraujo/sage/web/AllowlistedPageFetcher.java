package io.github.rennaraujo.sage.web;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Baixa uma página de fonte oficial sem deixar a requisição ser usada contra a rede interna (SSRF).
 *
 * <p>Regras, todas aplicadas a cada salto de redirect: só {@code https} na porta 443; host igual a um domínio da
 * allowlist ou subdomínio dele; o DNS é resolvido uma única vez, todos os endereços precisam ser públicos (nada de
 * loopback, rede privada, link-local, multicast, não especificado ou faixa reservada, em IPv4, IPv6 e IPv4 embutido em
 * IPv6) e a conexão é aberta direto no endereço validado. Como não há segunda resolução, um DNS que muda de resposta
 * entre a checagem e a conexão (DNS rebinding) não tem onde agir. O TLS usa o nome do host para SNI e para validar o
 * certificado, então o IP fixado não enfraquece a verificação.
 *
 * <p>Redirects são seguidos à mão (no máximo {@code max-redirects}). O corpo é lido até {@code max-page-bytes}; passou
 * disso a página é recusada, não truncada, porque HTML cortado no meio produziria documento errado. Um prazo único
 * ({@code fetch-timeout}) vale para a busca inteira, redirects incluídos, inclusive contra servidor que pinga bytes
 * devagar. Não há cookies, credenciais nem gravação em disco: tudo fica em memória.
 */
public final class AllowlistedPageFetcher {

    private static final Logger LOG = LoggerFactory.getLogger(AllowlistedPageFetcher.class);

    private static final String HTTPS = "https";
    private static final String HTTP = "http";
    private static final int HTTPS_PORT = 443;
    private static final int HTTP_OK = 200;
    private static final int REDIRECT_MIN = 300;
    private static final int REDIRECT_MAX = 399;
    private static final int MAX_LINE_BYTES = 8192;
    private static final int MAX_HEADER_LINES = 100;
    private static final int RADIX_HEX = 16;
    private static final int BUFFER_BYTES = 8192;
    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final String USER_AGENT = "Sage-Agent/1.0 (documentacao oficial)";
    private static final Charset DEFAULT_CHARSET = StandardCharsets.UTF_8;

    private final WebProperties properties;
    private final HostResolver resolver;
    private final boolean testMode;

    public AllowlistedPageFetcher(WebProperties properties) {
        this(properties, InetAddress::getAllByName, false);
    }

    /**
     * Construtor dos testes. Em {@code testMode} aceita {@code http}, qualquer porta e endereços locais, para falar
     * com um WireMock em localhost; a produção nunca liga isso (o construtor público fixa {@code false}).
     */
    AllowlistedPageFetcher(WebProperties properties, HostResolver resolver, boolean testMode) {
        this.properties = properties;
        this.resolver = resolver;
        this.testMode = testMode;
    }

    public Optional<FetchedPage> fetch(URI url, Set<String> allowedDomains) {
        if (url == null) {
            throw new IllegalArgumentException("url é obrigatória");
        }
        long deadline = System.nanoTime() + properties.fetchTimeout().toNanos();
        URI current = url;
        try {
            for (int hop = 0; hop <= properties.maxRedirects(); hop++) {
                Optional<Target> target = validate(current, allowedDomains);
                if (target.isEmpty()) {
                    return Optional.empty();
                }
                Response response = request(target.get(), deadline);
                if (response.status >= REDIRECT_MIN && response.status <= REDIRECT_MAX) {
                    if (response.location.isEmpty()) {
                        return Optional.empty();
                    }
                    current = current.resolve(response.location.get()).normalize();
                    continue;
                }
                if (response.status != HTTP_OK || response.body.isEmpty()) {
                    return Optional.empty();
                }
                return Optional.of(new FetchedPage(current, response.body.get()));
            }
            LOG.info("Página recusada: redirects demais");
            return Optional.empty();
        } catch (IOException | RuntimeException e) {
            // Só a classe: a mensagem de uma exceção de rede pode carregar endereço interno.
            LOG.info(
                    "Página não baixada (host={}, {})",
                    url.getHost(),
                    e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    // --- validação ---------------------------------------------------------------------------------------------

    private record Target(URI uri, String host, int port, boolean tls, InetAddress[] addresses) {}

    private Optional<Target> validate(URI uri, Set<String> allowedDomains) throws UnknownHostException {
        String scheme = uri.getScheme();
        boolean tls = HTTPS.equalsIgnoreCase(scheme);
        if (!tls && !(testMode && HTTP.equalsIgnoreCase(scheme))) {
            return refuse("esquema");
        }
        if (uri.getUserInfo() != null || uri.getHost() == null) {
            return refuse("autoridade");
        }
        int port = uri.getPort();
        if (!testMode && port != -1 && port != HTTPS_PORT) {
            return refuse("porta");
        }
        if (!AllowedHosts.matches(uri.getHost(), allowedDomains)) {
            return refuse("host fora da allowlist");
        }
        InetAddress[] addresses = resolver.resolve(uri.getHost());
        if (addresses.length == 0) {
            return refuse("sem endereço");
        }
        if (!testMode) {
            for (InetAddress address : addresses) {
                if (PublicAddresses.isBlocked(address)) {
                    return refuse("endereço não público");
                }
            }
        }
        int effectivePort = port != -1 ? port : (tls ? HTTPS_PORT : 80);
        return Optional.of(new Target(uri, uri.getHost(), effectivePort, tls, addresses));
    }

    /** Só o motivo vai para o log: o host recusado pode ser interno ou vir de uma página hostil. */
    private static Optional<Target> refuse(String reason) {
        LOG.info("Página recusada: {}", reason);
        return Optional.empty();
    }

    // --- HTTP/1.1 mínimo -----------------------------------------------------------------------------------------

    private record Response(int status, Optional<String> location, Optional<String> body) {}

    private Response request(Target target, long deadline) throws IOException {
        try (Socket socket = connect(target, deadline)) {
            OutputStream out = socket.getOutputStream();
            out.write(requestBytes(target));
            out.flush();
            InputStream in = socket.getInputStream();
            String statusLine = readLine(socket, in, deadline);
            int status = parseStatus(statusLine);
            Map<String, String> headers = readHeaders(socket, in, deadline);
            if (status >= REDIRECT_MIN && status <= REDIRECT_MAX) {
                return new Response(status, Optional.ofNullable(headers.get("location")), Optional.empty());
            }
            if (status != HTTP_OK || !isHtml(headers.get("content-type")) || isEncoded(headers)) {
                return new Response(status, Optional.empty(), Optional.empty());
            }
            byte[] body = readBody(socket, in, headers, deadline);
            return new Response(status, Optional.empty(), Optional.of(decode(body, headers)));
        }
    }

    private Socket connect(Target target, long deadline) throws IOException {
        IOException last = new IOException("sem endereço utilizável");
        for (InetAddress address : target.addresses()) {
            Socket socket = new Socket(Proxy.NO_PROXY);
            try {
                socket.connect(new InetSocketAddress(address, target.port()), remainingMillis(deadline));
                socket.setSoTimeout(remainingMillis(deadline));
                if (!target.tls()) {
                    return socket;
                }
                return handshake(socket, target, deadline);
            } catch (IOException e) {
                socket.close();
                last = e;
            }
        }
        throw last;
    }

    private SSLSocket handshake(Socket plain, Target target, long deadline) throws IOException {
        SSLSocket tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                .createSocket(plain, target.host(), target.port(), true);
        SSLParameters parameters = tls.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        tls.setSSLParameters(parameters);
        tls.setSoTimeout(remainingMillis(deadline));
        tls.startHandshake();
        return tls;
    }

    private static byte[] requestBytes(Target target) {
        URI uri = target.uri();
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
        boolean defaultPort = target.tls() && target.port() == HTTPS_PORT;
        String hostHeader = defaultPort ? target.host() : target.host() + ":" + target.port();
        String request = "GET " + path + query + " HTTP/1.1\r\n"
                + "Host: " + hostHeader + "\r\n"
                + "User-Agent: " + USER_AGENT + "\r\n"
                + "Accept: text/html,application/xhtml+xml\r\n"
                + "Accept-Encoding: identity\r\n"
                + "Connection: close\r\n\r\n";
        return request.getBytes(StandardCharsets.US_ASCII);
    }

    private static int parseStatus(String line) throws IOException {
        String[] parts = line.split(" ", 3);
        if (parts.length < 2 || !parts[0].startsWith("HTTP/")) {
            throw new IOException("resposta HTTP inválida");
        }
        try {
            return Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw new IOException("status inválido", e);
        }
    }

    private static Map<String, String> readHeaders(Socket socket, InputStream in, long deadline) throws IOException {
        Map<String, String> headers = new HashMap<>();
        for (int i = 0; i < MAX_HEADER_LINES; i++) {
            String line = readLine(socket, in, deadline);
            if (line.isEmpty()) {
                return headers;
            }
            int colon = line.indexOf(':');
            if (colon > 0) {
                headers.putIfAbsent(
                        line.substring(0, colon).strip().toLowerCase(Locale.ROOT),
                        line.substring(colon + 1).strip());
            }
        }
        throw new IOException("cabeçalhos demais");
    }

    private static boolean isHtml(String contentType) {
        if (contentType == null) {
            return false;
        }
        String type = contentType.toLowerCase(Locale.ROOT);
        return type.startsWith("text/html") || type.startsWith("application/xhtml+xml");
    }

    private static boolean isEncoded(Map<String, String> headers) {
        String encoding = headers.get("content-encoding");
        return encoding != null && !encoding.isBlank() && !"identity".equalsIgnoreCase(encoding.strip());
    }

    /** Charset do cabeçalho; sem ele, a detecção do jsoup ({@code <meta charset>}, BOM), com UTF-8 de último caso. */
    private static String decode(byte[] body, Map<String, String> headers) throws IOException {
        Optional<Charset> declared = headerCharset(headers);
        if (declared.isPresent()) {
            return new String(body, declared.get());
        }
        Charset detected = Jsoup.parse(new ByteArrayInputStream(body), null, "https://invalid.example/")
                .charset();
        return new String(body, detected == null ? DEFAULT_CHARSET : detected);
    }

    private static Optional<Charset> headerCharset(Map<String, String> headers) {
        String contentType = headers.getOrDefault("content-type", "");
        for (String part : contentType.split(";")) {
            String p = part.strip();
            if (p.toLowerCase(Locale.ROOT).startsWith("charset=")) {
                try {
                    return Optional.of(Charset.forName(
                            p.substring("charset=".length()).replace("\"", "").strip()));
                } catch (IllegalArgumentException e) {
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
    }

    private byte[] readBody(Socket socket, InputStream in, Map<String, String> headers, long deadline)
            throws IOException {
        long max = properties.maxPageBytes().toBytes();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        String transfer = headers.getOrDefault("transfer-encoding", "").toLowerCase(Locale.ROOT);
        if (transfer.contains("chunked")) {
            readChunked(socket, in, body, max, deadline);
        } else {
            String length = headers.get("content-length");
            long remaining = length == null ? Long.MAX_VALUE : parseLength(length);
            if (remaining > max && length != null) {
                throw new IOException("página acima do limite");
            }
            copy(socket, in, body, remaining, max, deadline);
        }
        return body.toByteArray();
    }

    private static long parseLength(String length) throws IOException {
        try {
            long value = Long.parseLong(length.strip());
            if (value < 0) {
                throw new IOException("content-length negativo");
            }
            return value;
        } catch (NumberFormatException e) {
            throw new IOException("content-length inválido", e);
        }
    }

    private static void readChunked(Socket socket, InputStream in, ByteArrayOutputStream body, long max, long deadline)
            throws IOException {
        while (true) {
            String sizeLine = readLine(socket, in, deadline);
            int semicolon = sizeLine.indexOf(';');
            String hex = (semicolon >= 0 ? sizeLine.substring(0, semicolon) : sizeLine).strip();
            long size;
            try {
                size = Long.parseLong(hex, RADIX_HEX);
            } catch (NumberFormatException e) {
                throw new IOException("chunk inválido", e);
            }
            if (size < 0) {
                throw new IOException("chunk inválido");
            }
            if (size == 0) {
                return;
            }
            if (body.size() + size > max) {
                throw new IOException("página acima do limite");
            }
            copy(socket, in, body, size, max, deadline);
            readLine(socket, in, deadline);
        }
    }

    /** Copia até {@code count} bytes (ou até o fim do fluxo, se {@code count} for ilimitado), respeitando o teto. */
    private static void copy(
            Socket socket, InputStream in, ByteArrayOutputStream out, long count, long max, long deadline)
            throws IOException {
        byte[] buffer = new byte[BUFFER_BYTES];
        long left = count;
        while (left > 0) {
            socket.setSoTimeout(remainingMillis(deadline));
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, left));
            if (read < 0) {
                if (count == Long.MAX_VALUE) {
                    return;
                }
                throw new IOException("fim inesperado do corpo");
            }
            if (out.size() + read > max) {
                throw new IOException("página acima do limite");
            }
            out.write(buffer, 0, read);
            left -= read;
        }
    }

    private static String readLine(Socket socket, InputStream in, long deadline) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (true) {
            socket.setSoTimeout(remainingMillis(deadline));
            int b = in.read();
            if (b < 0) {
                throw new IOException("conexão encerrada");
            }
            if (b == '\n') {
                break;
            }
            if (b != '\r') {
                line.write(b);
            }
            if (line.size() > MAX_LINE_BYTES) {
                throw new IOException("linha longa demais");
            }
        }
        return line.toString(StandardCharsets.ISO_8859_1);
    }

    private static int remainingMillis(long deadline) throws IOException {
        long left = (deadline - System.nanoTime()) / NANOS_PER_MILLI;
        if (left <= 0) {
            throw new IOException("tempo esgotado");
        }
        return (int) Math.min(left, Integer.MAX_VALUE);
    }
}
