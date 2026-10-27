package io.github.rennaraujo.sage.web;

import io.github.rennaraujo.sage.audit.AuditLog;
import io.github.rennaraujo.sage.document.DocumentStore;
import io.github.rennaraujo.sage.question.AliasCatalog;
import io.github.rennaraujo.sage.question.QuestionProperties;
import io.github.rennaraujo.sage.upload.SecretScanner;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WebProperties.class)
class WebConfig {

    private static final Logger LOG = LoggerFactory.getLogger(WebConfig.class);

    /** Folga do orçamento de preparação além do fallback inteiro: análise, busca, auditoria e a primeira escrita. */
    private static final Duration PREPARATION_MARGIN = Duration.ofSeconds(5);

    /** Com chave do Brave, busca de verdade; sem ela, uma busca que nunca acha nada e um aviso na subida. */
    @Bean
    WebSearch webSearch(WebProperties properties) {
        if (!properties.hasBraveKey()) {
            LOG.warn("SAGE_BRAVE_API_KEY ausente: o fallback web fica desligado e perguntas sem versão no acervo "
                    + "seguem sem fonte oficial.");
            return new NoopWebSearch();
        }
        return new BraveWebSearch(properties);
    }

    @Bean
    AllowlistedPageFetcher allowlistedPageFetcher(WebProperties properties) {
        return new AllowlistedPageFetcher(properties);
    }

    @Bean
    HtmlToMarkdown htmlToMarkdown() {
        return new HtmlToMarkdown();
    }

    @Bean
    WebQueryBuilder webQueryBuilder(AliasCatalog aliases) {
        return new WebQueryBuilder(aliases);
    }

    /**
     * O fallback inteiro (todas as versões somadas) roda dentro do orçamento de preparação da pergunta
     * ({@code sage.questions.preparation-timeout}), que também sustenta o SSE aberto; por isso o orçamento precisa
     * passar {@code sage.web.fallback-timeout} com folga.
     */
    @Bean
    WebFallback webFallback(
            WebSearch search,
            AllowlistedPageFetcher fetcher,
            HtmlToMarkdown converter,
            WebQueryBuilder queryBuilder,
            WebProperties properties,
            DocumentStore store,
            SecretScanner scanner,
            AuditLog audit,
            QuestionProperties questions) {
        if (questions
                        .preparationTimeout()
                        .compareTo(properties.fallbackTimeout().plus(PREPARATION_MARGIN))
                <= 0) {
            throw new IllegalStateException(
                    "sage.questions.preparation-timeout deve passar sage.web.fallback-timeout em pelo menos "
                            + PREPARATION_MARGIN.toSeconds() + " s (análise, busca e auditoria também cabem nele)");
        }
        return new WebFallbackService(
                search, fetcher, converter, queryBuilder, properties, store, scanner, audit, Clock.systemUTC());
    }
}
