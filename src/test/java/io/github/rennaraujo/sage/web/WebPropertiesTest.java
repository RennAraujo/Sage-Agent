package io.github.rennaraujo.sage.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.util.unit.DataSize;

class WebPropertiesTest {

    private static final Validator VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();

    private static WebProperties with(String domain) {
        return new WebProperties(
                Map.of("java", Set.of(domain)),
                3,
                Duration.ofSeconds(5),
                DataSize.ofMegabytes(2),
                3,
                "",
                URI.create("https://api.search.brave.com/res/v1/web/search"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "com",
                "localhost",
                ".docs.spring.io",
                "docs.spring.io.",
                "docs..spring.io",
                "",
                "a b.com",
                "docs.spring.io/x",
                "*.spring.io",
                "https://docs.spring.io",
                "127.0.0.1"
            })
    void rejectsMalformedDomains(String domain) {
        assertThat(VALIDATOR.validate(with(domain))).isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"docs.spring.io", "docs.oracle.com", "openjdk.org", "Docs.Spring.IO"})
    void acceptsRegularDomains(String domain) {
        assertThat(VALIDATOR.validate(with(domain))).isEmpty();
    }
}
