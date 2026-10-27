package io.github.rennaraujo.sage.upload;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.rennaraujo.sage.document.DocumentMetadata;
import java.time.LocalDate;
import java.util.Optional;

/** Só metadados: o texto do documento nunca sai pela listagem. Os nomes dos campos seguem o frontmatter. */
public record DocumentSummary(
        String id,
        @JsonProperty("fonte") String source,
        @JsonProperty("linguagem") String language,

        @JsonProperty("versao") @JsonInclude(JsonInclude.Include.NON_ABSENT)
        Optional<String> version,

        @JsonProperty("secao") String section,
        @JsonProperty("revisado") boolean reviewed,
        @JsonProperty("coletado_em") LocalDate collectedAt) {

    static DocumentSummary from(DocumentMetadata metadata) {
        return new DocumentSummary(
                metadata.id().value(),
                metadata.source().label(),
                metadata.language(),
                metadata.version(),
                metadata.section(),
                metadata.reviewed(),
                metadata.collectedAt());
    }
}
