package io.github.rennaraujo.sage.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.github.rennaraujo.sage.audit.AuditLog;
import io.github.rennaraujo.sage.document.DocumentStore;
import io.github.rennaraujo.sage.question.AliasCatalog;
import io.github.rennaraujo.sage.question.QuestionProperties;
import io.github.rennaraujo.sage.upload.SecretScanner;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.util.unit.DataSize;

class WebConfigTest {

    @Configuration(proxyBeanMethods = false)
    @Import(WebConfig.class)
    @EnableConfigurationProperties({WebProperties.class, QuestionProperties.class})
    static class Slice {}

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withBean(AliasCatalog.class, () -> mock(AliasCatalog.class))
            .withBean(DocumentStore.class, () -> mock(DocumentStore.class))
            .withBean(AuditLog.class, () -> mock(AuditLog.class))
            .withBean(SecretScanner.class, SecretScanner::new)
            .withUserConfiguration(Slice.class);

    @Test
    void bindsTheDefaultsFromApplicationYml() {
        runner.run(ctx -> {
            WebProperties p = ctx.getBean(WebProperties.class);
            assertThat(p.domainsFor("java")).containsExactlyInAnyOrder("docs.oracle.com", "openjdk.org");
            assertThat(p.domainsFor("spring-boot")).containsExactly("docs.spring.io");
            assertThat(p.domainsFor("spring-framework")).containsExactly("docs.spring.io");
            assertThat(p.domainsFor("spring-security")).containsExactly("docs.spring.io");
            assertThat(p.domainsFor("rust")).isEmpty();
            assertThat(p.maxPagesPerQuestion()).isEqualTo(3);
            assertThat(p.fetchTimeout()).isEqualTo(Duration.ofSeconds(5));
            assertThat(p.maxPageBytes()).isEqualTo(DataSize.ofMegabytes(2));
            assertThat(p.maxRedirects()).isEqualTo(3);
            assertThat(p.fallbackTimeout()).isEqualTo(Duration.ofSeconds(8));
            assertThat(ctx.getBean(QuestionProperties.class).preparationTimeout())
                    .isGreaterThan(p.fallbackTimeout());
            assertThat(ctx.getBean(WebFallback.class).active()).isFalse();
        });
    }

    @Test
    void withoutKeyTheSearchIsANoop() {
        runner.withPropertyValues("sage.web.brave-api-key=").run(ctx -> {
            assertThat(ctx.getBean(WebSearch.class)).isInstanceOf(NoopWebSearch.class);
            assertThat(ctx.getBean(WebSearch.class).search("q", Set.of("docs.spring.io"), 3))
                    .isEmpty();
        });
    }

    @Test
    void withKeyTheSearchIsBrave() {
        runner.withPropertyValues("sage.web.brave-api-key=abc")
                .run(ctx -> assertThat(ctx.getBean(WebSearch.class)).isInstanceOf(BraveWebSearch.class));
    }

    @Test
    void preparationTimeoutMustExceedTheFallbackTimeout() {
        runner.withPropertyValues("sage.questions.preparation-timeout=5s", "sage.web.fallback-timeout=8s")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void withKeyTheFallbackIsActive() {
        runner.withPropertyValues("sage.web.brave-api-key=abc")
                .run(ctx -> assertThat(ctx.getBean(WebFallback.class).active()).isTrue());
    }
}
