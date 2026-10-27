package io.github.rennaraujo.sage.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DocumentIdTest {

    @ParameterizedTest
    @ValueSource(strings = {"publico/java/25/stream-api.md", "interno/portal-x/faturamento.md"})
    void acceptsValid(String id) {
        assertThat(DocumentId.of(id).value()).isEqualTo(id);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "publico/java/../x.md",
                "publico/java/x.md",
                "outro/a/b.md",
                "interno/a/b.txt",
                "/publico/java/25/a.md",
                "publico//25/a.md",
                "publico/java/25/.md",
                "interno/a b/c.md",
                ""
            })
    void rejectsInvalid(String id) {
        assertThatThrownBy(() -> DocumentId.of(id)).isInstanceOf(InvalidDocumentException.class);
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> DocumentId.of(null)).isInstanceOf(InvalidDocumentException.class);
    }

    @Test
    void normalizesToLowercase() {
        assertThat(DocumentId.of("Publico/Java/25/A.md").value()).isEqualTo("publico/java/25/a.md");
    }

    @Test
    void derivesSourceFromPrefix() {
        assertThat(DocumentId.of("publico/java/25/a.md").source()).isEqualTo(Source.PUBLIC);
        assertThat(DocumentId.of("interno/app/a.md").source()).isEqualTo(Source.INTERNAL);
    }
}
