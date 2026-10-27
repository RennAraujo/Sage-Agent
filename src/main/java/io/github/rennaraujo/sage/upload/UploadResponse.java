package io.github.rennaraujo.sage.upload;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Resposta do POST: os ids gravados, na ordem processada, e quais deles substituíram um documento existente. */
public record UploadResponse(
        List<String> ids, @JsonProperty("substituidos") List<String> replaced) {}
