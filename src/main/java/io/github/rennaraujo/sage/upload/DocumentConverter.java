package io.github.rennaraujo.sage.upload;

import io.github.rennaraujo.sage.document.InvalidDocumentException;
import io.github.rennaraujo.sage.shared.HtmlMarkdown;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.apache.tika.config.ServiceLoader;
import org.apache.tika.detect.DefaultDetector;
import org.apache.tika.detect.Detector;
import org.apache.tika.detect.microsoft.POIFSContainerDetector;
import org.apache.tika.detect.ole.MiscOLEDetector;
import org.apache.tika.exception.TikaException;
import org.apache.tika.exception.WriteLimitReachedException;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.mime.MediaType;
import org.apache.tika.mime.MediaTypeRegistry;
import org.apache.tika.mime.MimeTypes;
import org.apache.tika.parser.EmptyParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.pdf.PDFParser;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.parser.txt.CharsetDetector;
import org.apache.tika.parser.txt.CharsetMatch;
import org.apache.tika.sax.BodyContentHandler;
import org.xml.sax.SAXException;

/**
 * Converte o arquivo enviado em Markdown. O tipo vem do conteúdo (detector do Tika); o nome do arquivo é só uma dica,
 * então um executável renomeado para {@code .md} continua sendo um executável e é recusado. Aceita Markdown, texto
 * puro, PDF, DOCX e HTML; o zip é tratado por quem chama (veja {@link #isZip}). Nada do texto vai para log.
 */
public final class DocumentConverter {

    private static final MediaType PDF = MediaType.application("pdf");
    private static final MediaType DOCX =
            MediaType.application("vnd.openxmlformats-officedocument.wordprocessingml.document");
    private static final MediaType ZIP = MediaType.application("zip");
    private static final MediaType PLAIN = MediaType.text("plain");
    private static final MediaType EMPTY = MediaType.application("x-empty");
    private static final Set<MediaType> HTML = Set.of(MediaType.text("html"), MediaType.application("xhtml+xml"));
    private static final Set<MediaType> MARKDOWN = Set.of(MediaType.text("markdown"), MediaType.text("x-web-markdown"));
    private static final Set<String> TEXT_EXTENSIONS = Set.of(".md", ".markdown", ".txt");
    private static final Charset FALLBACK_CHARSET = Charset.forName("windows-1252");
    private static final char BOM = '﻿';
    private static final int UTF16_BOM_BYTES = 2;
    private static final int BYTE_MASK = 0xFF;
    private static final int BOM_HIGH = 0xFF;
    private static final int BOM_LOW = 0xFE;

    private static final Pattern HEADING_STYLE = Pattern.compile("(?i)heading[1-6]");

    private enum Kind {
        TEXT,
        HTML,
        PDF,
        DOCX,
        ZIP,
        UNSUPPORTED
    }

    private final MediaTypeRegistry registry = MediaTypeRegistry.getDefaultRegistry();
    /**
     * Sem os detectores de contêiner OLE2: ao identificar um .doc/.xls antigo eles copiam o upload inteiro para um
     * apache-tika-*.tmp em claro no diretório temporário. Sem eles, um OLE2 sai como tipo genérico do Office, que não
     * está na lista de aceitos (415), e é exatamente o resultado que queremos.
     */
    private final Detector detector = new DefaultDetector(
            MimeTypes.getDefaultMimeTypes(),
            new ServiceLoader(DocumentConverter.class.getClassLoader()),
            List.of(POIFSContainerDetector.class, MiscOLEDetector.class));

    private final PDFParser pdfParser = new PDFParser();
    private final int maxChars;

    public DocumentConverter(UploadProperties properties) {
        this.maxChars = (int) Math.min(properties.maxFileBytes().toBytes(), Integer.MAX_VALUE);
        // O POI usa um limite global por entrada de zip (padrão 4 GB); aqui vale o teto do conteúdo descompactado.
        ZipSecureFile.setMaxEntrySize(properties.maxZipUncompressedBytes().toBytes());
    }

    public boolean isZip(byte[] content, String filename) {
        return classify(content, filename) == Kind.ZIP;
    }

    /** Devolve o Markdown (texto UTF-8). Lança {@link UnsupportedDocumentException} para tipos fora da lista. */
    public String toMarkdown(byte[] content, String filename) {
        return switch (classify(content, filename)) {
            case TEXT -> decode(content);
            case HTML -> HtmlMarkdown.fromHtml(decode(content));
            case PDF -> requireText(extractPdf(content, filename));
            case DOCX -> requireText(extractDocx(content));
            case ZIP, UNSUPPORTED ->
                throw new UnsupportedDocumentException(
                        "Tipo de arquivo não aceito: envie Markdown, texto, PDF, DOCX, HTML ou zip");
        };
    }

    private Kind classify(byte[] content, String filename) {
        if (content.length == 0) {
            throw new InvalidDocumentException("Arquivo vazio");
        }
        MediaType type = detect(content, filename);
        if (type.equals(EMPTY)) {
            throw new InvalidDocumentException("Arquivo vazio");
        }
        if (type.equals(PDF)) {
            return Kind.PDF;
        }
        if (type.equals(DOCX)) {
            return Kind.DOCX;
        }
        if (type.equals(ZIP)) {
            return Kind.ZIP;
        }
        if (HTML.contains(type)) {
            return Kind.HTML;
        }
        if (type.equals(PLAIN) || MARKDOWN.contains(type)) {
            return Kind.TEXT;
        }
        // Outros textos (código, YAML...) só passam se o nome os declara como texto ou Markdown.
        if (registry.isSpecializationOf(type, PLAIN) && hasTextExtension(filename)) {
            return Kind.TEXT;
        }
        return Kind.UNSUPPORTED;
    }

    private MediaType detect(byte[] content, String filename) {
        try (TikaInputStream in = TikaInputStream.get(content)) {
            return detector.detect(in, metadataFor(filename)).getBaseType();
        } catch (IOException e) {
            throw new InvalidDocumentException("Não foi possível ler o arquivo");
        }
    }

    private static Metadata metadataFor(String filename) {
        Metadata metadata = new Metadata();
        if (filename != null && !filename.isBlank()) {
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, filename);
        }
        return metadata;
    }

    private static boolean hasTextExtension(String filename) {
        if (filename == null) {
            return false;
        }
        String lower = filename.toLowerCase(Locale.ROOT);
        return TEXT_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    /** UTF-8 estrito primeiro (o caso comum); se não for UTF-8 válido, o detector do Tika escolhe o charset. */
    static String decode(byte[] bytes) {
        String text = decodeUtf16WithBom(bytes);
        if (text == null) {
            text = decodeStrictUtf8(bytes);
        }
        if (text == null) {
            text = new String(bytes, detectCharset(bytes));
        }
        return !text.isEmpty() && text.charAt(0) == BOM ? text.substring(1) : text;
    }

    private static String decodeUtf16WithBom(byte[] bytes) {
        if (bytes.length >= UTF16_BOM_BYTES) {
            int b0 = bytes[0] & BYTE_MASK;
            int b1 = bytes[1] & BYTE_MASK;
            if ((b0 == BOM_HIGH && b1 == BOM_LOW) || (b0 == BOM_LOW && b1 == BOM_HIGH)) {
                return new String(bytes, StandardCharsets.UTF_16);
            }
        }
        return null;
    }

    private static String decodeStrictUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private static Charset detectCharset(byte[] bytes) {
        CharsetDetector charsetDetector = new CharsetDetector();
        charsetDetector.setText(bytes);
        CharsetMatch match = charsetDetector.detect();
        if (match == null || !Charset.isSupported(match.getName())) {
            return FALLBACK_CHARSET;
        }
        return Charset.forName(match.getName());
    }

    private String extractPdf(byte[] content, String filename) {
        BodyContentHandler handler = new BodyContentHandler(maxChars);
        ParseContext context = new ParseContext();
        PDFParserConfig pdf = new PDFParserConfig();
        pdf.setOcrStrategy(PDFParserConfig.OCR_STRATEGY.NO_OCR);
        context.set(PDFParserConfig.class, pdf);
        // Sem parser para anexos e objetos embutidos: só o texto do próprio documento.
        context.set(Parser.class, new EmptyParser());
        try (TikaInputStream in = TikaInputStream.get(content)) {
            pdfParser.parse(in, handler, metadataFor(filename), context);
        } catch (SAXException e) {
            if (WriteLimitReachedException.isWriteLimitReached(e)) {
                throw new UploadTooLargeException("Texto extraído acima do limite de tamanho");
            }
            throw new InvalidDocumentException("Não foi possível extrair o texto do documento");
        } catch (IOException | TikaException e) {
            // Não encadeamos a causa: mensagens de parser podem citar trechos do arquivo.
            throw new InvalidDocumentException("Não foi possível extrair o texto do documento");
        }
        return handler.toString();
    }

    /**
     * DOCX direto pelo POI, em memória: o caminho do Tika para OOXML pede um arquivo no disco e copiaria o upload para
     * um apache-tika-*.tmp em claro. Títulos (estilos Heading1 a Heading6) viram # no Markdown; tabelas viram linhas
     * com células separadas por tabulação. A saída é limitada a {@code maxChars}.
     */
    private String extractDocx(byte[] content) {
        StringBuilder text = new StringBuilder();
        try (OPCPackage pkg = OPCPackage.open(new ByteArrayInputStream(content));
                XWPFDocument document = new XWPFDocument(pkg)) {
            for (IBodyElement element : document.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    appendParagraph(text, paragraph);
                } else if (element instanceof XWPFTable table) {
                    appendTable(text, table);
                }
                if (text.length() > maxChars) {
                    throw new UploadTooLargeException("Texto extraído acima do limite de tamanho");
                }
            }
        } catch (UploadTooLargeException e) {
            throw e;
        } catch (IOException | InvalidFormatException | RuntimeException e) {
            // Não encadeamos a causa: mensagens do POI podem citar trechos do arquivo.
            throw new InvalidDocumentException("Não foi possível extrair o texto do documento");
        }
        return text.toString();
    }

    private static void appendParagraph(StringBuilder out, XWPFParagraph paragraph) {
        String style = paragraph.getStyle();
        if (style != null && HEADING_STYLE.matcher(style).matches()) {
            out.append("#".repeat(style.charAt(style.length() - 1) - '0')).append(' ');
        }
        out.append(paragraph.getText()).append("\n\n");
    }

    private static void appendTable(StringBuilder out, XWPFTable table) {
        for (XWPFTableRow row : table.getRows()) {
            out.append(row.getTableCells().stream().map(XWPFTableCell::getText).collect(Collectors.joining("\t")))
                    .append('\n');
        }
        out.append('\n');
    }

    private static String requireText(String text) {
        if (text.isBlank()) {
            throw new InvalidDocumentException("O documento não tem texto extraível (PDF escaneado? não há OCR)");
        }
        return text.strip() + "\n";
    }
}
