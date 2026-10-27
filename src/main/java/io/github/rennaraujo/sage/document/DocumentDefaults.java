package io.github.rennaraujo.sage.document;

import java.util.Optional;

/** Valores usados quando o frontmatter do upload não traz o campo. */
public record DocumentDefaults(
        Optional<Source> source,
        Optional<String> language,
        Optional<String> version,
        Optional<String> section,
        boolean reviewed) {

    public static DocumentDefaults empty() {
        return new DocumentDefaults(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), false);
    }
}
