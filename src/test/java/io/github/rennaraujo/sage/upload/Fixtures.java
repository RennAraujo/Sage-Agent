package io.github.rennaraujo.sage.upload;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;

/**
 * Arquivos de exemplo gerados na hora com PDFBox e POI (já presentes via Tika), para não versionar binários. Ficam
 * com poucos KB.
 */
final class Fixtures {

    private static final float MARGIN = 50;
    private static final float TOP = 700;
    private static final float FONT_SIZE = 12;

    private Fixtures() {}

    static byte[] pdf(String line) {
        try (PDDocument document = new PDDocument();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), FONT_SIZE);
                content.newLineAtOffset(MARGIN, TOP);
                content.showText(line);
                content.endText();
            }
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Arquivo OLE2 (formato dos .doc antigos): uma stream "WordDocument" dentro de um POIFS. */
    static byte[] ole2() {
        try (org.apache.poi.poifs.filesystem.POIFSFileSystem fs =
                        new org.apache.poi.poifs.filesystem.POIFSFileSystem();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            fs.createDocument(new java.io.ByteArrayInputStream("OLE2-CANARIO".getBytes()), "WordDocument");
            fs.writeFilesystem(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] docx(String title, String paragraph) {
        try (XWPFDocument document = new XWPFDocument();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XWPFParagraph first = document.createParagraph();
            first.createRun().setText(title);
            XWPFParagraph second = document.createParagraph();
            second.createRun().setText(paragraph);
            document.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
