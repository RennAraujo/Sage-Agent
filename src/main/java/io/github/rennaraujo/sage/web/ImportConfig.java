package io.github.rennaraujo.sage.web;

import io.github.rennaraujo.sage.audit.AuditLog;
import io.github.rennaraujo.sage.auth.RateLimiter;
import io.github.rennaraujo.sage.document.DocumentStore;
import io.github.rennaraujo.sage.document.FrontmatterParser;
import io.github.rennaraujo.sage.upload.SecretScanner;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ImportProperties.class)
class ImportConfig {

    @Bean(destroyMethod = "shutdown")
    ImportService importService(
            AllowlistedPageFetcher fetcher,
            HtmlToMarkdown converter,
            DocumentStore store,
            FrontmatterParser parser,
            SecretScanner scanner,
            AuditLog auditLog,
            RateLimiter rateLimiter,
            WebProperties web,
            ImportProperties properties) {
        return new ImportService(
                fetcher, converter, store, parser, scanner, auditLog, rateLimiter, web, properties, Clock.systemUTC());
    }
}
