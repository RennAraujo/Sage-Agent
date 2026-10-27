package io.github.rennaraujo.sage.document;

import java.util.Arrays;
import java.util.Optional;

/** Origem do documento. No frontmatter aparece como "publico" ou "interno". */
public enum Source {
    PUBLIC("publico"),
    INTERNAL("interno");

    private final String label;

    Source(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static Optional<Source> fromLabel(String label) {
        return Arrays.stream(values()).filter(s -> s.label.equals(label)).findFirst();
    }
}
