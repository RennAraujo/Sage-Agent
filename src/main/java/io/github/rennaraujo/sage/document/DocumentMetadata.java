package io.github.rennaraujo.sage.document;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record DocumentMetadata(
        DocumentId id,
        Source source,
        String language,
        Optional<String> version,
        String section,
        List<String> aliases,
        Optional<URI> originUrl,
        LocalDate collectedAt,
        boolean reviewed) {

    public DocumentMetadata {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(language, "language");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(section, "section");
        Objects.requireNonNull(originUrl, "originUrl");
        Objects.requireNonNull(collectedAt, "collectedAt");
        aliases = List.copyOf(aliases);
    }
}
