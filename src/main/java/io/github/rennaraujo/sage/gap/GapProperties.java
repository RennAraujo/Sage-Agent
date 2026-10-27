package io.github.rennaraujo.sage.gap;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * {@code sage.gaps.*}: {@code maxEntries} é o teto de registros de lacuna guardados; ao passar dele, os mais antigos
 * são apagados na gravação.
 */
@Validated
@ConfigurationProperties("sage.gaps")
public record GapProperties(@Min(1) @DefaultValue("5000") int maxEntries) {}
