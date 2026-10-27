package io.github.rennaraujo.sage.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rennaraujo.sage.document.InvalidDocumentException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

class ZipExtractorTest {

    private static final int ENTRY_LIMIT_KB = 20;
    private static final int TOTAL_LIMIT_KB = 25;
    private static final int MAX_ENTRIES = 5;
    private static final int KB = 1024;

    private final ZipExtractor extractor = new ZipExtractor(new UploadProperties(
            DataSize.ofKilobytes(ENTRY_LIMIT_KB),
            DataSize.ofMegabytes(1),
            MAX_ENTRIES,
            DataSize.ofKilobytes(TOTAL_LIMIT_KB)));

    static byte[] zip(Map<String, byte[]> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue());
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    private static byte[] text(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static Map<String, byte[]> entries(String name, byte[] content) {
        Map<String, byte[]> map = new LinkedHashMap<>();
        map.put(name, content);
        return map;
    }

    @Test
    void extractsMarkdownAndTextEntriesIncludingNestedFolders() {
        Map<String, byte[]> map = new LinkedHashMap<>();
        map.put("a.md", text("um"));
        map.put("pasta/b.TXT", text("dois"));

        assertThat(extractor.extract(zip(map)))
                .extracting(ZipExtractor.Entry::path)
                .containsExactly("a.md", "pasta/b.TXT");
    }

    @Test
    void rejectsParentTraversal() {
        assertThatThrownBy(() -> extractor.extract(zip(entries("../evil.md", text("x")))))
                .isInstanceOf(InvalidDocumentException.class);
        assertThatThrownBy(() -> extractor.extract(zip(entries("pasta/../../evil.md", text("x")))))
                .isInstanceOf(InvalidDocumentException.class);
    }

    @Test
    void rejectsAbsoluteBackslashAndDrivePaths() {
        for (String name : new String[] {"/etc/evil.md", "pasta\\evil.md", "C:/evil.md", "C:evil.md"}) {
            assertThatThrownBy(() -> extractor.extract(zip(entries(name, text("x")))))
                    .as(name)
                    .isInstanceOf(InvalidDocumentException.class);
        }
    }

    @Test
    void ignoresDirectoriesMacosResourcesAndHiddenFiles() {
        Map<String, byte[]> map = new LinkedHashMap<>();
        map.put("pasta/", new byte[0]);
        map.put("__MACOSX/pasta/._a.md", text("lixo"));
        map.put(".obsidian/config.json", text("{}"));
        map.put("pasta/.oculto.md", text("oculto"));
        map.put("pasta/a.md", text("ok"));

        assertThat(extractor.extract(zip(map)))
                .extracting(ZipExtractor.Entry::path)
                .containsExactly("pasta/a.md");
    }

    @Test
    void rejectsEntriesThatAreNotMarkdownOrText() {
        assertThatThrownBy(() -> extractor.extract(zip(entries("imagem.png", new byte[] {1, 2, 3}))))
                .isInstanceOf(UnsupportedDocumentException.class);
    }

    @Test
    void rejectsTooManyEntries() {
        Map<String, byte[]> map = new LinkedHashMap<>();
        for (int i = 0; i <= MAX_ENTRIES; i++) {
            map.put("d" + i + ".md", text("x"));
        }

        assertThatThrownBy(() -> extractor.extract(zip(map))).isInstanceOf(UploadTooLargeException.class);
    }

    @Test
    void countsUncompressedBytesWhileReading() {
        byte[] big = text("a".repeat(ENTRY_LIMIT_KB * KB - 1));
        Map<String, byte[]> map = new LinkedHashMap<>();
        map.put("a.md", big);
        map.put("b.md", big);

        byte[] zip = zip(map);

        assertThat(zip.length).isLessThan(KB);
        assertThatThrownBy(() -> extractor.extract(zip)).isInstanceOf(UploadTooLargeException.class);
    }

    @Test
    void rejectsSingleEntryOverTheFileLimit() {
        byte[] big = new byte[ENTRY_LIMIT_KB * KB + 1];
        Arrays.fill(big, (byte) 'a');

        assertThatThrownBy(() -> extractor.extract(zip(entries("a.md", big))))
                .isInstanceOf(UploadTooLargeException.class);
    }

    @Test
    void truncatedZipIsInvalid() {
        Map<String, byte[]> map = new LinkedHashMap<>();
        map.put("a.md", text("conteudo-secreto-no-zip ".repeat(200)));
        byte[] zip = zip(map);
        byte[] truncated = Arrays.copyOf(zip, zip.length / 2);

        assertThatThrownBy(() -> extractor.extract(truncated))
                .isInstanceOf(InvalidDocumentException.class)
                .hasMessageNotContaining("conteudo-secreto");
    }

    @Test
    void corruptZipIsInvalidAndDoesNotEchoContent() {
        byte[] notAZip = text("PK\u0003\u0004 conteudo-secreto-no-lixo");

        assertThatThrownBy(() -> extractor.extract(notAZip))
                .isInstanceOf(InvalidDocumentException.class)
                .hasMessageNotContaining("conteudo-secreto");
    }
}
