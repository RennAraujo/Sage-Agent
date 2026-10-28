package io.github.rennaraujo.sage.upload;

import io.github.rennaraujo.sage.audit.AuditEvent;
import io.github.rennaraujo.sage.audit.AuditEventType;
import io.github.rennaraujo.sage.audit.AuditLog;
import io.github.rennaraujo.sage.auth.RateLimitedAction;
import io.github.rennaraujo.sage.auth.RateLimiter;
import io.github.rennaraujo.sage.document.Document;
import io.github.rennaraujo.sage.document.DocumentChangeNotPropagatedException;
import io.github.rennaraujo.sage.document.DocumentDefaults;
import io.github.rennaraujo.sage.document.DocumentId;
import io.github.rennaraujo.sage.document.DocumentMetadata;
import io.github.rennaraujo.sage.document.DocumentStore;
import io.github.rennaraujo.sage.document.FrontmatterParser;
import io.github.rennaraujo.sage.document.InvalidDocumentException;
import io.github.rennaraujo.sage.document.Source;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Clock;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Orquestra o upload: limite de requisições, tipo e tamanho, conversão para Markdown, varredura de segredos, montagem
 * do documento (id e metadados) e só então a gravação. Num zip, todas as entradas são validadas antes de gravar a
 * primeira: ou entra tudo, ou nada (a gravação em si, uma a uma, não é transacional). Cada gravação, revisão ou
 * remoção gera um evento de auditoria com o id do documento (a auditoria é cifrada) e nunca com o conteúdo.
 *
 * <p>O upload segue a mesma convenção da importação e do fallback web: {@code DOCUMENT_UPLOADED} com
 * {@code resultado=tentativa} para cada documento antes da primeira gravação (se falhar, nada é gravado) e, depois de
 * cada gravação, {@code salvo}, {@code existente} (outro envio gravou o mesmo id antes, e este recebe 409) ou
 * {@code falhou}.
 */
public final class UploadService {

    private static final int MAX_FIELD_CHARS = 200;
    private static final int MAX_SLUG_CHARS = 80;
    private static final int MAX_DISPLAY_NAME_CHARS = 100;
    private static final String DEFAULT_SLUG = "documento";
    private static final String DEFAULT_SECTION = "geral";
    private static final Pattern FRONTMATTER_ID = Pattern.compile("(?m)^id\\s*:\\s*\\S");
    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");
    private static final Pattern NON_SLUG = Pattern.compile("[^a-z0-9]+");
    private static final Pattern EDGE_DASHES = Pattern.compile("^-+|-+$");
    private static final char BOM = '﻿';
    private static final char LINE_SEPARATOR = ' ';
    private static final char PARAGRAPH_SEPARATOR = ' ';
    private static final String DELIMITER = "---";
    private static final String RESULT_ATTEMPT = "tentativa";
    private static final String RESULT_SAVED = "salvo";
    private static final String RESULT_EXISTING = "existente";
    private static final String RESULT_FAILED = "falhou";
    /** Gravado, mas um ouvinte da mudança (o índice) falhou. */
    private static final String WARNING_INDEX = "indice";

    private static final String MISSING_ID_FIELDS =
            "Sem id no frontmatter: informe os campos fonte e linguagem do envio (e versao para fonte publico)";

    private final DocumentStore store;
    private final FrontmatterParser parser;
    private final DocumentConverter converter;
    private final ZipExtractor zipExtractor;
    private final SecretScanner scanner;
    private final AuditLog auditLog;
    private final RateLimiter rateLimiter;
    private final UploadProperties properties;
    private final Clock clock;

    public UploadService(
            DocumentStore store,
            FrontmatterParser parser,
            DocumentConverter converter,
            ZipExtractor zipExtractor,
            SecretScanner scanner,
            AuditLog auditLog,
            RateLimiter rateLimiter,
            UploadProperties properties,
            Clock clock) {
        this.store = store;
        this.parser = parser;
        this.converter = converter;
        this.zipExtractor = zipExtractor;
        this.scanner = scanner;
        this.auditLog = auditLog;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.clock = clock;
    }

    public UploadResult upload(String actor, UploadRequest request) {
        rateLimiter.require(actor, RateLimitedAction.UPLOAD);
        Form form = Form.of(request, scanner);
        byte[] content = request.content();
        if (content.length > properties.maxZipBytes().toBytes()) {
            throw new UploadTooLargeException("Arquivo acima do limite de tamanho");
        }
        boolean zip = converter.isZip(content, request.filename());
        if (!zip && content.length > properties.maxFileBytes().toBytes()) {
            throw new UploadTooLargeException("Arquivo acima do limite de tamanho");
        }
        List<Incoming> incoming = zip
                ? zipExtractor.extract(content).stream()
                        .map(e -> new Incoming(e.content(), lastSegment(e.path()), Optional.of(e.path())))
                        .toList()
                : List.of(new Incoming(content, request.filename(), Optional.empty()));
        if (incoming.isEmpty()) {
            throw new InvalidDocumentException("O zip não tem nenhum arquivo .md ou .txt");
        }
        List<Document> documents =
                incoming.stream().map(in -> prepare(in, form)).toList();
        requireDistinctIds(documents);
        List<DocumentId> replaced = documents.stream()
                .map(d -> d.metadata().id())
                .filter(id -> store.find(id).isPresent())
                .toList();
        if (!replaced.isEmpty() && !request.replace()) {
            throw new DocumentConflictException();
        }
        // Auditoria antes de qualquer gravação: se ela falhar, o acervo fica como estava.
        for (Document document : documents) {
            auditUpload(actor, document, replaced, RESULT_ATTEMPT);
        }
        boolean conflict = false;
        for (Document document : documents) {
            conflict |= !write(actor, document, replaced, request.replace());
        }
        if (conflict) {
            // Outro envio gravou o mesmo id entre a checagem acima e a gravação: o dele fica, este recebe 409.
            throw new DocumentConflictException();
        }
        return new UploadResult(documents.stream().map(d -> d.metadata().id()).toList(), replaced);
    }

    /**
     * Grava um documento e audita o desfecho ({@code salvo}, {@code existente} ou {@code falhou}). Sem
     * {@code substituir}, a gravação é {@link DocumentStore#saveIfAbsent}: dois envios simultâneos do mesmo id passam
     * os dois pela checagem de conflito, e só a gravação atômica decide quem fica. Devolve {@code false} quando o id já
     * existia.
     */
    private boolean write(String actor, Document document, List<DocumentId> replaced, boolean replace) {
        boolean written;
        try {
            if (replace) {
                store.save(document);
                written = true;
            } else {
                written = store.saveIfAbsent(document);
            }
        } catch (Throwable t) { // NOPMD - o desfecho é auditado em qualquer falha, inclusive Error
            try {
                if (t instanceof DocumentChangeNotPropagatedException) {
                    // Gravado; quem falhou foi um ouvinte (o índice). Dizer "falhou" seria mentir sobre o acervo.
                    auditUpload(actor, document, replaced, RESULT_SAVED, Optional.of(WARNING_INDEX));
                } else {
                    auditUpload(actor, document, replaced, RESULT_FAILED, Optional.empty());
                }
            } catch (RuntimeException auditFailure) {
                t.addSuppressed(auditFailure);
            }
            throw t;
        }
        auditUpload(actor, document, replaced, written ? RESULT_SAVED : RESULT_EXISTING, Optional.empty());
        return written;
    }

    private void auditUpload(String actor, Document document, List<DocumentId> replaced, String result) {
        auditUpload(actor, document, replaced, result, Optional.empty());
    }

    private void auditUpload(
            String actor, Document document, List<DocumentId> replaced, String result, Optional<String> warning) {
        DocumentId id = document.metadata().id();
        Map<String, String> details = new LinkedHashMap<>();
        details.put("document", id.value());
        details.put("bytes", Integer.toString(utf8Length(document.body())));
        if (replaced.contains(id)) {
            details.put("substituido", "true");
        }
        details.put("resultado", result);
        warning.ifPresent(w -> details.put("aviso", w));
        audit(AuditEventType.DOCUMENT_UPLOADED, actor, details);
    }

    public List<DocumentMetadata> list() {
        return store.listMetadata();
    }

    public void review(String actor, String rawId, boolean reviewed) {
        DocumentId id = DocumentId.of(rawId);
        Document current = store.find(id).orElseThrow(DocumentNotFoundException::new);
        DocumentMetadata m = current.metadata();
        DocumentMetadata updated = new DocumentMetadata(
                m.id(),
                m.source(),
                m.language(),
                m.version(),
                m.section(),
                m.aliases(),
                m.originUrl(),
                m.collectedAt(),
                reviewed);
        audit(
                AuditEventType.DOCUMENT_REVIEWED,
                actor,
                Map.of("document", id.value(), "revisado", Boolean.toString(reviewed)));
        store.save(new Document(updated, current.body()));
    }

    public void delete(String actor, String rawId) {
        DocumentId id = DocumentId.of(rawId);
        if (store.find(id).isEmpty()) {
            throw new DocumentNotFoundException();
        }
        audit(AuditEventType.DOCUMENT_DELETED, actor, Map.of("document", id.value()));
        store.delete(id);
    }

    private void audit(AuditEventType type, String actor, Map<String, String> details) {
        auditLog.record(new AuditEvent(type, actor, clock.instant(), details));
    }

    private Document prepare(Incoming incoming, Form form) {
        String markdown = converter.toMarkdown(incoming.content(), incoming.filename());
        if (utf8Length(markdown) > properties.maxFileBytes().toBytes()) {
            throw new UploadTooLargeException("Texto convertido acima do limite de tamanho");
        }
        List<SecretFinding> findings = scanner.scan(markdown);
        if (!findings.isEmpty()) {
            throw new SecretsFoundException(findings, incoming.zipPath().map(UploadService::displayName));
        }
        return assemble(markdown, incoming.filename(), form);
    }

    /** O id do frontmatter, se houver, vale; sem ele, o id sai dos campos do envio e do nome do arquivo. */
    private Document assemble(String markdown, String filename, Form form) {
        DocumentDefaults defaults = form.defaults(sectionFallback(filename));
        if (hasFrontmatter(markdown)) {
            if (frontmatterHasId(markdown)) {
                return parser.parse(markdown, defaults);
            }
            Form merged = form.mergedWith(FrontmatterHints.of(frontmatterBlock(markdown)));
            return parser.parse(markdown, deriveId(merged, filename), merged.defaults(sectionFallback(filename)));
        }
        DocumentId id = deriveId(form, filename);
        return parser.parse(frontmatterFor(id, form, sectionFallback(filename)) + markdown, defaults);
    }

    private static DocumentId deriveId(Form form, String filename) {
        Source source = form.source().orElseThrow(() -> new InvalidDocumentException(MISSING_ID_FIELDS));
        String language = form.language().orElseThrow(() -> new InvalidDocumentException(MISSING_ID_FIELDS));
        String slug = slug(filename) + ".md";
        if (source == Source.PUBLIC) {
            String version = form.version().orElseThrow(() -> new InvalidDocumentException(MISSING_ID_FIELDS));
            return DocumentId.of(Source.PUBLIC.label() + "/" + language + "/" + version + "/" + slug);
        }
        return DocumentId.of(Source.INTERNAL.label() + "/" + language + "/" + slug);
    }

    private static String frontmatterFor(DocumentId id, Form form, String sectionFallback) {
        StringBuilder out = new StringBuilder(DELIMITER).append('\n');
        line(out, "id", id.value());
        line(out, "fonte", id.source().label());
        line(out, "linguagem", form.language().orElseThrow(() -> new InvalidDocumentException(MISSING_ID_FIELDS)));
        form.version().ifPresent(v -> line(out, "versao", v));
        line(out, "secao", form.section().orElse(sectionFallback));
        form.originUrl().ifPresent(u -> line(out, "url_origem", u));
        return out.append(DELIMITER).append('\n').toString();
    }

    /** Valores entre aspas duplas (YAML); os campos já foram validados sem caracteres de controle. */
    private static void line(StringBuilder out, String key, String value) {
        out.append(key)
                .append(": \"")
                .append(value.replace("\\", "\\\\").replace("\"", "\\\""))
                .append("\"\n");
    }

    private static boolean hasFrontmatter(String markdown) {
        String text = stripBom(markdown);
        return text.startsWith(DELIMITER + "\n") || text.startsWith(DELIMITER + "\r\n");
    }

    private static boolean frontmatterHasId(String markdown) {
        return FRONTMATTER_ID.matcher(frontmatterBlock(markdown)).find();
    }

    private static String frontmatterBlock(String markdown) {
        String text = stripBom(markdown).replace("\r\n", "\n").replace('\r', '\n');
        int end = text.indexOf("\n" + DELIMITER, DELIMITER.length());
        return end < 0 ? text : text.substring(0, end);
    }

    /** Só o último trecho do caminho da entrada, limitado: o nome vem do zip e não passa pela varredura de segredos. */
    private static String displayName(String path) {
        String name = lastSegment(path);
        return name.length() > MAX_DISPLAY_NAME_CHARS ? name.substring(0, MAX_DISPLAY_NAME_CHARS) : name;
    }

    private static String stripBom(String text) {
        return !text.isEmpty() && text.charAt(0) == BOM ? text.substring(1) : text;
    }

    private static void requireDistinctIds(List<Document> documents) {
        Set<DocumentId> seen = new HashSet<>();
        for (Document document : documents) {
            if (!seen.add(document.metadata().id())) {
                throw new InvalidDocumentException("Dois arquivos do envio resultam no mesmo id de documento");
            }
        }
    }

    private static int utf8Length(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    private static String lastSegment(String path) {
        String normalized = path == null ? "" : path.replace('\\', '/');
        return normalized.substring(normalized.lastIndexOf('/') + 1);
    }

    /** Nome do arquivo sem extensão, usado como seção quando nem o frontmatter nem o formulário informam uma. */
    private static String sectionFallback(String filename) {
        String name = stem(filename).strip();
        return name.isEmpty() || hasControl(name) ? DEFAULT_SECTION : name;
    }

    private static String stem(String filename) {
        String name = lastSegment(filename);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** Minúsculas, sem acento, só {@code [a-z0-9-]}. */
    public static String slug(String filename) {
        String ascii = DIACRITICS
                .matcher(Normalizer.normalize(stem(filename), Normalizer.Form.NFD))
                .replaceAll("");
        String slug = EDGE_DASHES
                .matcher(NON_SLUG.matcher(ascii.toLowerCase(Locale.ROOT)).replaceAll("-"))
                .replaceAll("");
        if (slug.length() > MAX_SLUG_CHARS) {
            slug = EDGE_DASHES.matcher(slug.substring(0, MAX_SLUG_CHARS)).replaceAll("");
        }
        return slug.isEmpty() ? DEFAULT_SLUG : slug;
    }

    private static boolean hasControl(String value) {
        return value.chars()
                .anyMatch(c -> Character.isISOControl(c) || c == LINE_SEPARATOR || c == PARAGRAPH_SEPARATOR);
    }

    private record Incoming(byte[] content, String filename, Optional<String> zipPath) {}

    /** Campos do formulário já limpos: sem espaços nas pontas, sem caracteres de controle, tamanho limitado. */
    private record Form(
            Optional<Source> source,
            Optional<String> language,
            Optional<String> version,
            Optional<String> section,
            Optional<String> originUrl) {

        static Form of(UploadRequest request, SecretScanner scanner) {
            Optional<Source> source =
                    clean("fonte", request.source(), scanner).map(label -> toSource(label.toLowerCase(Locale.ROOT)));
            return new Form(
                    source,
                    clean("linguagem", request.language(), scanner).map(v -> v.toLowerCase(Locale.ROOT)),
                    clean("versao", request.version(), scanner).map(v -> v.toLowerCase(Locale.ROOT)),
                    clean("secao", request.section(), scanner),
                    clean("url_origem", request.originUrl(), scanner));
        }

        /** Completa o formulário com o que o frontmatter diz; se os dois dizem coisas diferentes, recusa. */
        Form mergedWith(FrontmatterHints hints) {
            return new Form(
                    agree("fonte", source, hints.source().map(Form::toSource)),
                    agree("linguagem", language, hints.language()),
                    agree("versao", version, hints.version()),
                    section,
                    originUrl);
        }

        private static Source toSource(String label) {
            return Source.fromLabel(label)
                    .orElseThrow(() -> new InvalidDocumentException("Campo fonte deve ser \"publico\" ou \"interno\""));
        }

        private static <T> Optional<T> agree(String field, Optional<T> fromForm, Optional<T> fromFrontmatter) {
            if (fromForm.isPresent() && fromFrontmatter.isPresent() && !fromForm.equals(fromFrontmatter)) {
                throw new InvalidDocumentException("Campo " + field + " do formulário contradiz o frontmatter");
            }
            return fromForm.or(() -> fromFrontmatter);
        }

        DocumentDefaults defaults(String sectionFallback) {
            return new DocumentDefaults(source, language, version, Optional.of(section.orElse(sectionFallback)), false);
        }

        private static Optional<String> clean(String field, Optional<String> raw, SecretScanner scanner) {
            Optional<String> value = raw.map(String::strip).filter(v -> !v.isEmpty());
            value.ifPresent(v -> {
                if (v.length() > MAX_FIELD_CHARS || hasControl(v)) {
                    throw new InvalidDocumentException(
                            "Campo " + field + " inválido: muito longo ou com caracteres de controle");
                }
                List<SecretFinding> findings = scanner.scan(v);
                if (!findings.isEmpty()) {
                    throw new SecretsFoundException(findings, Optional.of("campo " + field));
                }
            });
            return value;
        }
    }

    /** Fonte, linguagem e versão como escritas no frontmatter (texto, em minúsculas), se houver. */
    private record FrontmatterHints(Optional<String> source, Optional<String> language, Optional<String> version) {

        static FrontmatterHints of(String block) {
            return new FrontmatterHints(scalar(block, "fonte"), scalar(block, "linguagem"), scalar(block, "versao"));
        }

        private static Optional<String> scalar(String block, String key) {
            Matcher matcher =
                    Pattern.compile("(?m)^" + key + "[ \\t]*:[ \\t]*(.*)$").matcher(block);
            if (!matcher.find()) {
                return Optional.empty();
            }
            String value = matcher.group(1).strip();
            if (value.startsWith("\"") || value.startsWith("'")) {
                int end = value.indexOf(value.charAt(0), 1);
                value = end > 0 ? value.substring(1, end) : value.substring(1);
            } else {
                int comment = value.indexOf(" #");
                value = comment >= 0 ? value.substring(0, comment) : value;
            }
            value = value.strip().toLowerCase(Locale.ROOT);
            return value.isEmpty() ? Optional.empty() : Optional.of(value);
        }
    }

    /** Resultado do envio: os ids gravados e, entre eles, os que já existiam e foram substituídos. */
    public record UploadResult(List<DocumentId> ids, List<DocumentId> replaced) {}
}
