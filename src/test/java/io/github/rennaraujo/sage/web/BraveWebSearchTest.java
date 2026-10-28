package io.github.rennaraujo.sage.web;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

class BraveWebSearchTest {

    private static final Set<String> DOMAINS = Set.of("docs.oracle.com", "openjdk.org");
    private static WireMockServer wire;

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

    private static BraveWebSearch search(String key) {
        return new BraveWebSearch(
                new WebProperties(
                        Map.of("java", DOMAINS),
                        3,
                        Duration.ofSeconds(2),
                        DataSize.ofMegabytes(2),
                        3,
                        key,
                        URI.create("http://localhost:" + wire.port() + "/res/v1/web/search")),
                true);
    }

    @Test
    void closeShutsTheHttpClientDown() {
        BraveWebSearch search = search("chave");

        search.close();

        assertThat(search.closed()).isTrue();
    }

    @Test
    void ignoresNonAllowlistedResults() {
        wire.stubFor(get(urlPathEqualTo("/res/v1/web/search"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"web":{"results":[
                                  {"url":"https://evil.com/java"},
                                  {"url":"https://docs.oracle.com/en/java/javase/25/"},
                                  {"url":"http://docs.oracle.com/plain-http"},
                                  {"url":"https://docs.oracle.com.evil.com/x"},
                                  {"url":"https://evildocs.oracle.com.br/x"},
                                  {"url":"https://openjdk.org/jeps/444"},
                                  {"url":"not a url"},
                                  {"url":"https://docs.oracle.com/en/java/javase/25/"}
                                ]}}
                                """)));

        List<URI> found = search("chave-secreta").search("Java 25 streams", DOMAINS, 10);

        assertThat(found)
                .containsExactly(
                        URI.create("https://docs.oracle.com/en/java/javase/25/"),
                        URI.create("https://openjdk.org/jeps/444"));
    }

    @Test
    void sendsTokenAndOrJoinedSiteQuery() {
        wire.stubFor(get(urlPathEqualTo("/res/v1/web/search"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{}")));

        search("chave-secreta").search("Java 25 streams", DOMAINS, 10);

        wire.verify(getRequestedFor(urlPathEqualTo("/res/v1/web/search"))
                .withHeader("X-Subscription-Token", equalTo("chave-secreta"))
                .withQueryParam("count", equalTo("10"))
                .withQueryParam("q", equalTo("Java 25 streams (site:docs.oracle.com OR site:openjdk.org)")));
    }

    @Test
    void respectsTheLimit() {
        wire.stubFor(get(urlPathEqualTo("/res/v1/web/search"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"web":{"results":[{"url":"https://openjdk.org/a"},{"url":"https://openjdk.org/b"},
                                {"url":"https://openjdk.org/c"}]}}
                                """)));

        assertThat(search("k").search("q", DOMAINS, 2)).hasSize(2);
    }

    @Test
    void returnsEmptyOnErrorsAndGarbage() {
        wire.stubFor(
                get(urlPathEqualTo("/res/v1/web/search")).willReturn(aResponse().withStatus(429)));
        assertThat(search("k").search("q", DOMAINS, 5)).isEmpty();

        wire.stubFor(get(urlPathEqualTo("/res/v1/web/search"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("<html>")));
        assertThat(search("k").search("q", DOMAINS, 5)).isEmpty();
    }

    @Test
    void productionRequiresAnHttpsEndpoint() {
        WebProperties plain = new WebProperties(
                Map.of("java", DOMAINS),
                3,
                Duration.ofSeconds(2),
                DataSize.ofMegabytes(2),
                3,
                "k",
                URI.create("http://api.search.brave.com/res/v1/web/search"));

        assertThatThrownBy(() -> new BraveWebSearch(plain)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void malformedResultsAreSkippedNotFatal() {
        wire.stubFor(get(urlPathEqualTo("/res/v1/web/search"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"web":{"results":[
                                  {"url":null}, {"url":12}, {"url":{"a":1}}, "texto", 7, {},
                                  {"url":"https://docs.oracle.com/valid"}
                                ]}}
                                """)));

        assertThat(search("k").search("q", DOMAINS, 5)).containsExactly(URI.create("https://docs.oracle.com/valid"));
    }

    @Test
    void slowResponseBodiesAreCutAtTheTimeout() {
        wire.stubFor(get(urlPathEqualTo("/res/v1/web/search"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"web\":{\"results\":[" + "{\"url\":\"https://openjdk.org/a\"},".repeat(40)
                                + "{\"url\":\"https://openjdk.org/z\"}]}}")
                        .withChunkedDribbleDelay(40, 4_000)));
        BraveWebSearch slow = new BraveWebSearch(
                new WebProperties(
                        Map.of("java", DOMAINS),
                        3,
                        Duration.ofMillis(600),
                        DataSize.ofMegabytes(2),
                        3,
                        "k",
                        URI.create("http://localhost:" + wire.port() + "/res/v1/web/search")),
                true);
        long start = System.nanoTime();

        assertThat(slow.search("q", DOMAINS, 5)).isEmpty();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(2_500));
    }

    @Test
    void noDomainsMeansNoRequest() {
        assertThat(search("k").search("q", Set.of(), 5)).isEmpty();
        assertThat(wire.getAllServeEvents()).isEmpty();
    }

    @Test
    void noopSearchReturnsNothing() {
        assertThat(new NoopWebSearch().search("q", DOMAINS, 5)).isEmpty();
    }

    @Test
    void propertiesNeverPrintTheKey() {
        WebProperties p = new WebProperties(
                Map.of("java", DOMAINS),
                3,
                Duration.ofSeconds(2),
                DataSize.ofMegabytes(2),
                3,
                "chave-secreta",
                URI.create("https://api.search.brave.com/res/v1/web/search"));

        assertThat(p.toString()).doesNotContain("chave-secreta");
        assertThat(p.hasBraveKey()).isTrue();
    }
}
