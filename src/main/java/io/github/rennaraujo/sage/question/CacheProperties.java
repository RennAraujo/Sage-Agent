package io.github.rennaraujo.sage.question;

import jakarta.validation.constraints.Min;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Cache de respostas ({@code sage.cache.*}), só em memória. {@code ttl} é o tempo máximo de uma resposta no cache e
 * {@code maxEntries} limita quantas ficam guardadas ao mesmo tempo.
 */
@Validated
@ConfigurationProperties("sage.cache")
public record CacheProperties(
        @DefaultValue("6h") Duration ttl,
        @Min(1) @DefaultValue("1000") int maxEntries) {

    public CacheProperties {
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("sage.cache.ttl precisa ser positivo");
        }
    }
}
