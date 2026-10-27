package io.github.rennaraujo.sage.web;

import io.github.rennaraujo.sage.audit.AuditLog;
import io.github.rennaraujo.sage.document.DocumentStore;
import io.github.rennaraujo.sage.question.AliasCatalog;
import io.github.rennaraujo.sage.upload.SecretScanner;
import java.net.InetAddress;
import java.time.Clock;

/**
 * Acesso de teste ao que o código de produção não oferece: o fetcher em modo de teste (http, qualquer porta,
 * loopback) e as regras de versão de URL aplicadas ao host local do WireMock. Fica no pacote {@code web} dos testes
 * para alcançar os construtores de pacote.
 */
public final class WebTestSupport {

    /** Host local que segue as regras de {@code docs.oracle.com}. */
    public static final String TEST_HOST = "docs.example.test";

    private WebTestSupport() {}

    /** Resolve qualquer host permitido para o loopback, onde o WireMock escuta. */
    public static AllowlistedPageFetcher plainHttpFetcher(WebProperties properties) {
        HostResolver loopback = host -> new InetAddress[] {InetAddress.getLoopbackAddress()};
        return new AllowlistedPageFetcher(properties, loopback, true);
    }

    public static WebFallbackService fallback(
            WebSearch search,
            WebProperties properties,
            AliasCatalog catalog,
            DocumentStore store,
            AuditLog audit,
            Clock clock) {
        return new WebFallbackService(
                search,
                plainHttpFetcher(properties),
                new HtmlToMarkdown(),
                new WebQueryBuilder(catalog),
                properties,
                store,
                new SecretScanner(),
                audit,
                clock,
                VersionedUrls.production().withAlias(TEST_HOST, "docs.oracle.com"));
    }

    /** O buscador usado em produção quando não há chave do Brave. */
    public static WebSearch noopSearch() {
        return new NoopWebSearch();
    }
}
