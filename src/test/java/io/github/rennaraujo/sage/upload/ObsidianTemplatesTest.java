package io.github.rennaraujo.sage.upload;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rennaraujo.sage.document.Document;
import io.github.rennaraujo.sage.document.DocumentDefaults;
import io.github.rennaraujo.sage.document.FrontmatterParser;
import io.github.rennaraujo.sage.document.Source;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** Os templates do Obsidian precisam ser frontmatter válido e passar na varredura de segredos. */
class ObsidianTemplatesTest {

    private final FrontmatterParser parser = new FrontmatterParser();

    private String load(String name) throws IOException {
        try (InputStream in = new ClassPathResource("templates/obsidian/" + name).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void publicTemplateIsAValidPublicDocument() throws IOException {
        String raw = load("documento-publico.md");

        Document doc = parser.parse(raw, DocumentDefaults.empty());

        assertThat(doc.metadata().source()).isEqualTo(Source.PUBLIC);
        assertThat(doc.metadata().version()).isPresent();
        assertThat(doc.metadata().originUrl()).isPresent();
        assertThat(new SecretScanner().scan(raw)).isEmpty();
    }

    @Test
    void internalTemplateIsAValidInternalDocument() throws IOException {
        String raw = load("documento-interno.md");

        Document doc = parser.parse(raw, DocumentDefaults.empty());

        assertThat(doc.metadata().source()).isEqualTo(Source.INTERNAL);
        assertThat(doc.metadata().id().value()).isEqualTo("interno/portal-x/faturamento.md");
        assertThat(new SecretScanner().scan(raw)).isEmpty();
    }
}
