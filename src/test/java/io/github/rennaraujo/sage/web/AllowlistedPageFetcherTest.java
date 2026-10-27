package io.github.rennaraujo.sage.web;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.util.unit.DataSize;

class AllowlistedPageFetcherTest {

    private static final String HOST = "docs.example.test";
    private static final Set<String> DOMAINS = Set.of("example.test");
    private static final Duration TIMEOUT = Duration.ofMillis(700);

    private static WireMockServer wire;

    private final HostResolver loopback = host -> new InetAddress[] {InetAddress.getLoopbackAddress()};

    @BeforeAll
    static void start() {
        wire = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wire.start();
    }

    @AfterAll
    static void stop() {
        wire.stop();
    }

    @BeforeEach
    void reset() {
        wire.resetAll();
    }

    private static WebProperties props(DataSize maxBytes, int maxRedirects) {
        return new WebProperties(
                Map.of("java", DOMAINS),
                3,
                TIMEOUT,
                maxBytes,
                maxRedirects,
                "",
                URI.create("https://api.search.brave.com/res/v1/web/search"));
    }

    private AllowlistedPageFetcher testFetcher(DataSize maxBytes, int maxRedirects) {
        return new AllowlistedPageFetcher(props(maxBytes, maxRedirects), loopback, true);
    }

    private URI page(String path) {
        return URI.create("http://" + HOST + ":" + wire.port() + path);
    }

    private static AllowlistedPageFetcher production(HostResolver resolver) {
        return new AllowlistedPageFetcher(props(DataSize.ofMegabytes(1), 3), resolver, false);
    }

    @Test
    void fetchesAllowlistedPage() {
        wire.stubFor(get("/guide")
                .willReturn(aResponse()
                        .withHeader("Content-Type", "text/html; charset=UTF-8")
                        .withBody("<html><body><h1>Olá</h1></body></html>")));

        Optional<FetchedPage> got = testFetcher(DataSize.ofKilobytes(64), 3).fetch(page("/guide"), DOMAINS);

        assertThat(got).isPresent();
        assertThat(got.get().html()).contains("<h1>Olá</h1>");
        assertThat(got.get().url()).isEqualTo(page("/guide"));
    }

    @Test
    void readsChunkedBodies() {
        wire.stubFor(get("/chunked")
                .willReturn(aResponse()
                        .withHeader("Content-Type", "text/html")
                        .withBody("<p>pedaço um</p><p>pedaço dois</p>")
                        .withChunkedDribbleDelay(4, 20)));

        Optional<FetchedPage> got = testFetcher(DataSize.ofKilobytes(64), 3).fetch(page("/chunked"), DOMAINS);

        assertThat(got).isPresent();
        assertThat(got.get().html()).isEqualTo("<p>pedaço um</p><p>pedaço dois</p>");
    }

    @Test
    void rejectsRedirectToOtherHost() {
        wire.stubFor(get("/moved")
                .willReturn(
                        aResponse().withStatus(302).withHeader("Location", "http://evil.test:" + wire.port() + "/x")));
        wire.stubFor(get("/x").willReturn(aResponse().withBody("<p>x</p>")));

        // evil.test resolve para o mesmo WireMock: se o fetcher seguisse, a requisição apareceria aqui.
        Optional<FetchedPage> got = testFetcher(DataSize.ofKilobytes(64), 3).fetch(page("/moved"), DOMAINS);

        assertThat(got).isEmpty();
        wire.verify(0, getRequestedFor(urlEqualTo("/x")));
    }

    @Test
    void followsRedirectsInsideTheAllowlistUpToTheLimit() {
        wire.stubFor(get("/a").willReturn(aResponse().withStatus(301).withHeader("Location", "/b")));
        wire.stubFor(get("/b").willReturn(aResponse().withStatus(302).withHeader("Location", "/c")));
        wire.stubFor(get("/c")
                .willReturn(aResponse().withHeader("Content-Type", "text/html").withBody("<p>fim</p>")));

        assertThat(testFetcher(DataSize.ofKilobytes(64), 2).fetch(page("/a"), DOMAINS))
                .hasValueSatisfying(p -> assertThat(p.url()).isEqualTo(page("/c")));
        assertThat(testFetcher(DataSize.ofKilobytes(64), 1).fetch(page("/a"), DOMAINS))
                .isEmpty();
    }

    @Test
    void rejectsHttpScheme() {
        // Configuração de produção: http nunca sai da máquina, nem chega a resolver o DNS.
        HostResolver mustNotBeCalled = host -> {
            throw new AssertionError("não deveria resolver DNS para http");
        };

        assertThat(production(mustNotBeCalled).fetch(page("/guide"), DOMAINS)).isEmpty();
        assertThat(production(mustNotBeCalled).fetch(URI.create("http://" + HOST + "/guide"), DOMAINS))
                .isEmpty();
        wire.verify(0, getRequestedFor(urlEqualTo("/guide")));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://evil.com/",
                "https://example.test.evil.com/",
                "https://notexample.test/",
                "ftp://docs.example.test/f",
                "https://docs.example.test:8443/x",
                "https://user@docs.example.test/x",
                "https://[::1]/",
                "https://127.0.0.1/"
            })
    void rejectsHostsAndSchemesOutsideTheAllowlist(String url) {
        HostResolver mustNotBeCalled = host -> {
            throw new AssertionError("não deveria resolver DNS");
        };

        assertThat(production(mustNotBeCalled).fetch(URI.create(url), DOMAINS)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "127.0.0.1",
                "10.1.2.3",
                "172.16.0.9",
                "192.168.1.1",
                "169.254.169.254",
                "0.0.0.0",
                "224.0.0.1",
                "100.64.0.1",
                "::1",
                "::",
                "fe80::1",
                "fc00::1",
                "ff02::1",
                "::ffff:127.0.0.1",
                "::ffff:10.0.0.1",
                "64:ff9b::7f00:1",
                "2001:0:4136:e378:8000:63bf:3fff:fdd2",
                "64:ff9b:1::808:808",
                "100::1",
                "2001:db8::1"
            })
    void rejectsPrivateAddress(String address) throws UnknownHostException {
        InetAddress resolved = InetAddress.getByName(address);
        HostResolver resolver = host -> new InetAddress[] {resolved};

        assertThat(production(resolver).fetch(URI.create("https://" + HOST + "/x"), DOMAINS))
                .isEmpty();
    }

    @Test
    void rejectsWhenAnyResolvedAddressIsPrivate() throws UnknownHostException {
        HostResolver mixed =
                host -> new InetAddress[] {InetAddress.getByName("93.184.216.34"), InetAddress.getByName("10.0.0.5")};

        assertThat(production(mixed).fetch(URI.create("https://" + HOST + "/x"), DOMAINS))
                .isEmpty();
    }

    @Test
    void rejectsHostThatDoesNotResolve() {
        HostResolver nx = host -> {
            throw new UnknownHostException(host);
        };

        assertThat(production(nx).fetch(URI.create("https://" + HOST + "/x"), DOMAINS))
                .isEmpty();
    }

    @Test
    void enforcesMaxBytes() {
        wire.stubFor(get("/big")
                .willReturn(aResponse()
                        .withHeader("Content-Type", "text/html")
                        .withBody("<p>" + "a".repeat(50_000) + "</p>")));
        wire.stubFor(get("/small")
                .willReturn(aResponse()
                        .withHeader("Content-Type", "text/html")
                        .withBody("<p>" + "a".repeat(1_000) + "</p>")));
        AllowlistedPageFetcher fetcher = testFetcher(DataSize.ofKilobytes(16), 3);

        assertThat(fetcher.fetch(page("/big"), DOMAINS)).isEmpty();
        assertThat(fetcher.fetch(page("/small"), DOMAINS)).isPresent();
    }

    @Test
    void enforcesMaxBytesOnChunkedBodiesWithoutContentLength() {
        wire.stubFor(get("/bigchunked")
                .willReturn(aResponse()
                        .withHeader("Content-Type", "text/html")
                        .withBody("<p>" + "a".repeat(50_000) + "</p>")
                        .withChunkedDribbleDelay(10, 10)));

        assertThat(testFetcher(DataSize.ofKilobytes(16), 3).fetch(page("/bigchunked"), DOMAINS))
                .isEmpty();
    }

    @Test
    void enforcesTimeout() {
        wire.stubFor(get("/slow")
                .willReturn(aResponse()
                        .withHeader("Content-Type", "text/html")
                        .withBody("<p>tarde</p>")
                        .withFixedDelay(3_000)));
        long start = System.nanoTime();

        Optional<FetchedPage> got = testFetcher(DataSize.ofKilobytes(64), 3).fetch(page("/slow"), DOMAINS);

        assertThat(got).isEmpty();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(2_500));
    }

    @Test
    void enforcesTimeoutOnSlowDrippedBodies() {
        wire.stubFor(get("/drip")
                .willReturn(aResponse()
                        .withHeader("Content-Type", "text/html")
                        .withBody("<p>" + "a".repeat(400) + "</p>")
                        .withChunkedDribbleDelay(40, 3_000)));
        long start = System.nanoTime();

        assertThat(testFetcher(DataSize.ofKilobytes(64), 3).fetch(page("/drip"), DOMAINS))
                .isEmpty();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(2_500));
    }

    @Test
    void rejectsNonHtmlContentAndErrorStatus() {
        wire.stubFor(get("/bin")
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/zip")
                        .withBody("PK")));
        wire.stubFor(get("/gone")
                .willReturn(aResponse()
                        .withStatus(404)
                        .withHeader("Content-Type", "text/html")
                        .withBody("<p>não achei</p>")));
        AllowlistedPageFetcher fetcher = testFetcher(DataSize.ofKilobytes(64), 3);

        assertThat(fetcher.fetch(page("/bin"), DOMAINS)).isEmpty();
        assertThat(fetcher.fetch(page("/gone"), DOMAINS)).isEmpty();
    }

    @Test
    void nullUrlIsAnIllegalArgument() {
        assertThatThrownBy(() -> testFetcher(DataSize.ofKilobytes(64), 3).fetch(null, DOMAINS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesNegativeContentLength() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread thread = Thread.ofVirtual().start(() -> {
                try (Socket s = server.accept()) {
                    s.getInputStream().read(new byte[1024]);
                    s.getOutputStream()
                            .write("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: -5\r\n\r\n<p>x</p>"
                                    .getBytes(StandardCharsets.US_ASCII));
                } catch (IOException ignored) {
                    // o cliente pode fechar antes
                }
            });
            URI url = URI.create("http://" + HOST + ":" + server.getLocalPort() + "/n");

            assertThat(testFetcher(DataSize.ofKilobytes(64), 3).fetch(url, DOMAINS))
                    .isEmpty();
            thread.join(2_000);
        }
    }

    @Test
    void usesTheMetaCharsetWhenTheHeaderHasNone() {
        String html = "<html><head><meta charset=\"iso-8859-1\"></head><body><p>ação</p></body></html>";
        wire.stubFor(get("/latin")
                .willReturn(aResponse()
                        .withHeader("Content-Type", "text/html")
                        .withBody(html.getBytes(Charset.forName("ISO-8859-1")))));
        wire.stubFor(get("/utf8")
                .willReturn(aResponse()
                        .withHeader("Content-Type", "text/html")
                        .withBody("<html><body><p>ação</p></body></html>".getBytes(StandardCharsets.UTF_8))));
        AllowlistedPageFetcher fetcher = testFetcher(DataSize.ofKilobytes(64), 3);

        assertThat(fetcher.fetch(page("/latin"), DOMAINS))
                .hasValueSatisfying(p -> assertThat(p.html()).contains("ação"));
        assertThat(fetcher.fetch(page("/utf8"), DOMAINS))
                .hasValueSatisfying(p -> assertThat(p.html()).contains("ação"));
    }

    @Test
    void neverLogsHostsOutsideTheAllowlist() {
        Logger logger = (Logger) LoggerFactory.getLogger(AllowlistedPageFetcher.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            HostResolver none = host -> new InetAddress[0];
            production(none).fetch(URI.create("https://segredo-interno.evil.example/x"), DOMAINS);
            production(none).fetch(URI.create("https://10.9.8.7/x"), DOMAINS);
            wire.stubFor(get("/r")
                    .willReturn(
                            aResponse().withStatus(302).withHeader("Location", "http://redirecionado.evil.example/x")));
            testFetcher(DataSize.ofKilobytes(64), 3).fetch(page("/r"), DOMAINS);

            assertThat(appender.list).isNotEmpty().allSatisfy(e -> {
                assertThat(e.getFormattedMessage())
                        .doesNotContain("evil.example")
                        .doesNotContain("10.9.8.7");
                assertThat(
                                e.getArgumentArray() == null
                                        ? ""
                                        : String.join(
                                                ",",
                                                java.util.Arrays.stream(e.getArgumentArray())
                                                        .map(String::valueOf)
                                                        .toList()))
                        .doesNotContain("evil.example");
            });
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void sendsNoCookiesAndNoCredentials() {
        wire.stubFor(get("/c")
                .willReturn(aResponse()
                        .withHeader("Content-Type", "text/html")
                        .withHeader("Set-Cookie", "sid=1")
                        .withBody("<p>ok</p>")));
        AllowlistedPageFetcher fetcher = testFetcher(DataSize.ofKilobytes(64), 3);

        fetcher.fetch(page("/c"), DOMAINS);
        fetcher.fetch(page("/c"), DOMAINS);

        assertThat(wire.findAll(getRequestedFor(urlEqualTo("/c")))).hasSize(2).allSatisfy(r -> {
            assertThat(r.getHeaders().getHeader("Cookie").isPresent()).isFalse();
            assertThat(r.getHeaders().getHeader("Authorization").isPresent()).isFalse();
        });
    }
}
