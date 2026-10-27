package io.github.rennaraujo.sage.auth;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Requisições por minuto, por chave de API e por ação. */
@Validated
@ConfigurationProperties("sage.rate-limit")
public record RateLimitProperties(
        @Min(1) @DefaultValue("20") int questionsPerMinute,
        @Min(1) @DefaultValue("10") int uploadsPerMinute) {

    int limitFor(RateLimitedAction action) {
        return switch (action) {
            case QUESTION -> questionsPerMinute;
            case UPLOAD -> uploadsPerMinute;
        };
    }
}
