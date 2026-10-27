package io.github.rennaraujo.sage.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rennaraujo.sage.document.InvalidDocumentException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

class DocumentConverterTest {

    private static final int TINY_LIMIT_BYTES = 50;
    private static final int PAD = 300;

    private final DocumentConverter converter = new DocumentConverter(
            new UploadProperties(DataSize.ofMegabytes(1), DataSize.ofMegabytes(2), 10, DataSize.ofMegabytes(4)));

    @Test
    void conversionAndDetectionNeverSpoolToTheTempDir() throws Exception {
        java.nio.file.Path tmp = java.nio.file.Path.of(System.getProperty("java.io.tmpdir"));
        Map<String, byte[]> zipEntries = new LinkedHashMap<>();
        zipEntries.put("a.md", "x".getBytes(StandardCharsets.UTF_8));
        try (TempWatcher watcher = new TempWatcher(tmp)) {
            converter.toMarkdown(Fixtures.docx("Título", "Corpo DOCX"), "a.docx");
            converter.toMarkdown(Fixtures.pdf("Corpo PDF"), "a.pdf");
            converter.isZip(ZipExtractorTest.zip(zipEntries), "a.zip");
            assertThatThrownBy(() -> converter.toMarkdown(Fixtures.ole2(), "velho.doc"))
                    .isInstanceOf(UnsupportedDocumentException.class);
            assertThatThrownBy(() -> converter.toMarkdown(Fixtures.ole2(), "disfarcado.md"))
                    .isInstanceOf(UnsupportedDocumentException.class);

            assertThat(watcher.created()).noneMatch(name -> name.startsWith("apache-tika") || name.startsWith("poi"));
        }
    }

    @Test
    void docxHeadingStylesBecomeMarkdownHeadings() throws Exception {
        try (org.apache.poi.xwpf.usermodel.XWPFDocument doc = new org.apache.poi.xwpf.usermodel.XWPFDocument();
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            org.apache.poi.xwpf.usermodel.XWPFParagraph title = doc.createParagraph();
            title.setStyle("Heading2");
            title.createRun().setText("Faturamento");
            doc.createParagraph().createRun().setText("Corpo");
            doc.write(out);

            assertThat(converter.toMarkdown(out.toByteArray(), "a.docx"))
                    .startsWith("## Faturamento")
                    .contains("Corpo");
        }
    }

    @Test
    void markdownPassesThroughUnchanged() {
        String md = "---\nid: interno/a/b.md\n---\n# Título\n\ntexto com acentuação\n";

        assertThat(converter.toMarkdown(md.getBytes(StandardCharsets.UTF_8), "b.md"))
                .isEqualTo(md);
    }

    @Test
    void latin1AndWindows1252AreDecodedWithoutLoss() {
        String text = "# Configuração\n\nA configuração do módulo de faturamento é feita pela variável de ambiente, "
                + "e o serviço de cobrança também lê essa informação na inicialização.\n";

        assertThat(converter.toMarkdown(text.getBytes(StandardCharsets.ISO_8859_1), "a.txt"))
                .isEqualTo(text);
        assertThat(converter.toMarkdown(text.getBytes(java.nio.charset.Charset.forName("windows-1252")), "a.txt"))
                .isEqualTo(text);
    }

    @Test
    void utf8BomAndUtf16AreNormalized() {
        String text = "# Título\n\nação\n";
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] withBom = concat(bom, text.getBytes(StandardCharsets.UTF_8));

        assertThat(converter.toMarkdown(withBom, "a.md")).isEqualTo(text);
        assertThat(converter.toMarkdown(text.getBytes(StandardCharsets.UTF_16), "a.txt"))
                .isEqualTo(text);
    }

    @Test
    void htmlIsSanitizedAndConvertedToMarkdown() {
        String html = "<html><body><h1>Guia</h1><p>Texto <b>forte</b> e <a href=\"https://exemplo.com\">link</a>.</p>"
                + "<script>alert('XSS-CANARIO')</script><ul><li>um</li><li>dois</li></ul></body></html>";

        String md = converter.toMarkdown(html.getBytes(StandardCharsets.UTF_8), "guia.html");

        assertThat(md).contains("# Guia", "**forte**", "[link](https://exemplo.com)", "* um", "* dois");
        assertThat(md).doesNotContain("XSS-CANARIO", "script");
    }

    @Test
    void pdfAndDocxTextIsExtracted() {
        assertThat(converter.toMarkdown(Fixtures.pdf("Texto do PDF PDF-CANARIO-777"), "a.pdf"))
                .contains("PDF-CANARIO-777");
        assertThat(converter.toMarkdown(Fixtures.docx("Título DOCX", "Parágrafo DOCX-CANARIO-888"), "a.docx"))
                .contains("Título DOCX", "Parágrafo DOCX-CANARIO-888");
    }

    @Test
    void pdfWithoutTextIsInvalid() {
        assertThatThrownBy(() -> converter.toMarkdown(Fixtures.pdf(" "), "vazio.pdf"))
                .isInstanceOf(InvalidDocumentException.class);
    }

    @Test
    void extractedTextOverTheLimitIsTooLarge() {
        DocumentConverter tiny = new DocumentConverter(new UploadProperties(
                DataSize.ofBytes(TINY_LIMIT_BYTES), DataSize.ofMegabytes(2), 10, DataSize.ofMegabytes(4)));

        assertThatThrownBy(() -> tiny.toMarkdown(Fixtures.docx("A".repeat(PAD), "B".repeat(PAD)), "a.docx"))
                .isInstanceOf(UploadTooLargeException.class);
    }

    @Test
    void executableContentIsUnsupportedEvenWithMarkdownName() {
        assertThatThrownBy(() -> converter.toMarkdown(windowsExecutable(), "leia-me.md"))
                .isInstanceOf(UnsupportedDocumentException.class);
        assertThatThrownBy(() -> converter.toMarkdown(elfExecutable(), "notas.txt"))
                .isInstanceOf(UnsupportedDocumentException.class);
    }

    @Test
    void zipAndEmptyFilesAreNotConvertible() {
        Map<String, byte[]> map = new LinkedHashMap<>();
        map.put("a.md", "x".getBytes(StandardCharsets.UTF_8));
        byte[] zip = ZipExtractorTest.zip(map);

        assertThat(converter.isZip(zip, "pacote.zip")).isTrue();
        assertThat(converter.isZip("texto".getBytes(StandardCharsets.UTF_8), "a.md"))
                .isFalse();
        assertThatThrownBy(() -> converter.toMarkdown(zip, "pacote.zip"))
                .isInstanceOf(UnsupportedDocumentException.class);
        assertThatThrownBy(() -> converter.toMarkdown(new byte[0], "a.md"))
                .isInstanceOf(InvalidDocumentException.class);
    }

    static byte[] windowsExecutable() {
        byte[] bytes = new byte[PAD];
        bytes[0] = 'M';
        bytes[1] = 'Z';
        bytes[2] = (byte) 0x90;
        bytes[2 + 2] = 3;
        return bytes;
    }

    static byte[] elfExecutable() {
        byte[] bytes = new byte[PAD];
        bytes[0] = 0x7F;
        bytes[1] = 'E';
        bytes[2] = 'L';
        bytes[3] = 'F';
        bytes[4] = 2;
        bytes[5] = 1;
        return bytes;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
