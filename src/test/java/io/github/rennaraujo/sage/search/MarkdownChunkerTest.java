package io.github.rennaraujo.sage.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class MarkdownChunkerTest {

    private static final int MAX_CHARS = 1500;

    private final MarkdownChunker chunker = new MarkdownChunker(MAX_CHARS);

    @Test
    void splitsByHeadingsWithPath() {
        String md = """
                Texto antes de qualquer título.

                # Streams
                Visão geral dos streams.

                ## toList
                Retorna uma lista imutável.

                ## collect
                Acumula em um coletor.

                # Optional
                Contêiner de valor opcional.
                """;

        List<MarkdownChunker.Section> sections = chunker.split(md);

        assertThat(sections)
                .extracting(MarkdownChunker.Section::headingPath)
                .containsExactly("", "Streams", "Streams > toList", "Streams > collect", "Optional");
        assertThat(sections.get(2).text()).contains("lista imutável").doesNotContain("coletor");
    }

    @Test
    void neverSplitsCodeBlock() {
        String code = "```java\n" + "int x = 0;\n".repeat(60) + "```";
        String md = "# Exemplo\n" + "Parágrafo de introdução. ".repeat(30) + "\n\n" + code + "\n\nFim.\n";

        List<MarkdownChunker.Section> sections = chunker.split(md);

        assertThat(sections.stream().filter(s -> s.text().contains("```java")))
                .singleElement()
                .satisfies(s -> assertThat(s.text()).contains(code));
    }

    @Test
    void ignoresHeadingMarkersInsideCodeBlock() {
        String md = "# Real\n```bash\n# comentário de shell\necho oi\n```\n";

        List<MarkdownChunker.Section> sections = chunker.split(md);

        assertThat(sections).singleElement().satisfies(s -> {
            assertThat(s.headingPath()).isEqualTo("Real");
            assertThat(s.text()).contains("# comentário de shell");
        });
    }

    @Test
    void respectsMaxChars() {
        StringBuilder md = new StringBuilder("# Longo\n");
        for (int i = 0; i < 80; i++) {
            md.append("Frase número ").append(i).append(" com algum conteúdo para encorpar o parágrafo. ");
            if (i % 7 == 6) {
                md.append("\n\n");
            }
        }
        // Um parágrafo único gigante, sem quebras, também precisa ser dividido.
        md.append("\n\n").append("palavra ".repeat(600));

        List<MarkdownChunker.Section> sections = chunker.split(md.toString());

        assertThat(sections).hasSizeGreaterThan(2);
        assertThat(sections).allSatisfy(s -> {
            assertThat(s.text().length()).isLessThanOrEqualTo(MAX_CHARS);
            assertThat(s.headingPath()).isEqualTo("Longo");
        });
    }

    @Test
    void blankInputGivesNoSections() {
        assertThat(chunker.split("")).isEmpty();
        assertThat(chunker.split("# Só título\n\n")).isEmpty();
    }

    @Test
    void supportsSetextHeadings() {
        String md = "Streams\n=======\nvisão geral\n\ntoList\n------\nlista imutável\n";

        List<MarkdownChunker.Section> sections = chunker.split(md);

        assertThat(sections)
                .extracting(MarkdownChunker.Section::headingPath)
                .containsExactly("Streams", "Streams > toList");
        assertThat(sections.get(1).text()).isEqualTo("lista imutável");
    }

    @Test
    void tildeFenceKeepsCodeAndIgnoresHeadingMarkers() {
        String md = "# Real\n~~~\n# não é título\ncódigo\n~~~\ndepois\n";

        List<MarkdownChunker.Section> sections = chunker.split(md);

        assertThat(sections).singleElement().satisfies(s -> {
            assertThat(s.headingPath()).isEqualTo("Real");
            assertThat(s.text()).contains("# não é título").contains("depois");
        });
    }

    @Test
    void fenceIndentedUpToThreeSpacesIsStillAFence() {
        String md = "# Real\n   ```\n# não é título\n   ```\n";

        List<MarkdownChunker.Section> sections = chunker.split(md);

        assertThat(sections).singleElement().satisfies(s -> {
            assertThat(s.headingPath()).isEqualTo("Real");
            assertThat(s.text()).contains("# não é título");
        });
    }
}
