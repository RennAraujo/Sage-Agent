package io.github.rennaraujo.sage.upload;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

/** Corpo do PATCH: {@code {"revisado": true}}. */
public record ReviewRequest(
        @JsonProperty("revisado") @NotNull Boolean reviewed) {}
