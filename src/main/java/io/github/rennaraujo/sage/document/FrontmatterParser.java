package io.github.rennaraujo.sage.document;

import java.net.URI;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.resolver.Resolver;

/**
 * Lê e escreve documentos Markdown com frontmatter YAML. O YAML é carregado com {@link SafeConstructor} (sem tags de
 * objeto) e sem resolução implícita de tipos: todo escalar chega como texto, então {@code versao: 3.20} continua
 * "3.20" e não vira o número 3.2. Mensagens de erro citam o campo, nunca o conteúdo.
 */
public final class FrontmatterParser {

    private static final String BOM = "﻿";
    private static final String DELIMITER = "---";
    private static final int ESCAPE_LIMIT = 0x20;
    private static final int NEXT_LINE = 0x85;
    private static final int LINE_SEPARATOR = 0x2028;
    private static final int PARAGRAPH_SEPARATOR = 0x2029;
    private static final Set<String> HTTP_SCHEMES = Set.of("http", "https");

    private static final String ID = "id";
    private static final String SOURCE = "fonte";
    private static final String LANGUAGE = "linguagem";
    private static final String VERSION = "versao";
    private static final String SECTION = "secao";
    private static final String ALIASES = "aliases";
    private static final String ORIGIN_URL = "url_origem";
    private static final String COLLECTED_AT = "coletado_em";
    private static final String REVIEWED = "revisado";

    private final Clock clock;

    public FrontmatterParser() {
        this(Clock.systemDefaultZone());
    }

    public FrontmatterParser(Clock clock) {
        this.clock = clock;
    }

    /** O id precisa estar no frontmatter. */
    public Document parse(String raw, DocumentDefaults defaults) {
        return parse(raw, Optional.empty(), defaults);
    }

    /** Usa o id informado por quem chama (por exemplo, derivado do caminho do upload). */
    public Document parse(String raw, DocumentId id, DocumentDefaults defaults) {
        return parse(raw, Optional.of(id), defaults);
    }

    public String render(Document doc) {
        DocumentMetadata meta = doc.metadata();
        StringBuilder out = new StringBuilder();
        out.append(DELIMITER).append('\n');
        line(out, ID, quote(meta.id().value()));
        line(out, SOURCE, meta.source().label());
        line(out, LANGUAGE, quote(meta.language()));
        meta.version().ifPresent(v -> line(out, VERSION, quote(v)));
        line(out, SECTION, quote(meta.section()));
        if (!meta.aliases().isEmpty()) {
            List<String> quoted =
                    meta.aliases().stream().map(FrontmatterParser::quote).toList();
            line(out, ALIASES, "[" + String.join(", ", quoted) + "]");
        }
        meta.originUrl().ifPresent(u -> line(out, ORIGIN_URL, quote(u.toString())));
        line(out, COLLECTED_AT, quote(meta.collectedAt().toString()));
        line(out, REVIEWED, Boolean.toString(meta.reviewed()));
        out.append(DELIMITER).append('\n');
        out.append(doc.body());
        return out.toString();
    }

    private Document parse(String raw, Optional<DocumentId> givenId, DocumentDefaults defaults) {
        if (raw == null) {
            throw new InvalidDocumentException("Documento ausente");
        }
        String text = normalize(raw);
        int[] bounds = frontmatterBounds(text);
        Map<String, Object> fields = loadYaml(text.substring(DELIMITER.length() + 1, bounds[0]));
        String body = text.substring(bounds[1]);
        return new Document(buildMetadata(fields, givenId, defaults), body);
    }

    private static String normalize(String raw) {
        String text = raw.startsWith(BOM) ? raw.substring(BOM.length()) : raw;
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    /** Devolve {fim do YAML, início do corpo}. */
    private static int[] frontmatterBounds(String text) {
        if (!text.startsWith(DELIMITER + "\n")) {
            throw new InvalidDocumentException("Frontmatter ausente: o documento precisa começar com ---");
        }
        int position = DELIMITER.length() + 1;
        while (position <= text.length()) {
            int newline = text.indexOf('\n', position);
            int end = newline < 0 ? text.length() : newline;
            if (text.substring(position, end).stripTrailing().equals(DELIMITER)) {
                return new int[] {position, newline < 0 ? text.length() : newline + 1};
            }
            if (newline < 0) {
                break;
            }
            position = newline + 1;
        }
        throw new InvalidDocumentException("Frontmatter sem a linha --- de fechamento");
    }

    private static Map<String, Object> loadYaml(String yamlText) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setAllowRecursiveKeys(false);
        DumperOptions dumper = new DumperOptions();
        Yaml yaml =
                new Yaml(new SafeConstructor(options), new Representer(dumper), dumper, options, new PlainResolver());
        Object loaded;
        try {
            loaded = yaml.load(yamlText);
        } catch (YAMLException | ClassCastException e) {
            // A mensagem do SnakeYAML cita trechos do texto; não repassamos nem encadeamos.
            throw new InvalidDocumentException(
                    "Frontmatter YAML inválido (sintaxe, tag não permitida ou chave repetida)");
        }
        if (loaded == null) {
            return Map.of();
        }
        if (!(loaded instanceof Map<?, ?> map)) {
            throw new InvalidDocumentException("Frontmatter deve ser um mapa de campos");
        }
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new InvalidDocumentException("Frontmatter com chave que não é texto");
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    private DocumentMetadata buildMetadata(
            Map<String, Object> fields, Optional<DocumentId> givenId, DocumentDefaults d) {
        DocumentId id = resolveId(fields, givenId);
        Source source = text(fields, SOURCE)
                .map(label -> Source.fromLabel(label)
                        .orElseThrow(
                                () -> new InvalidDocumentException("Campo fonte deve ser \"publico\" ou \"interno\"")))
                .or(() -> d.source())
                .orElse(id.source());
        if (source != id.source()) {
            throw new InvalidDocumentException("Campo fonte contradiz o prefixo do id");
        }
        String language = text(fields, LANGUAGE).or(() -> d.language()).orElseThrow(() -> missing(LANGUAGE));
        String section = text(fields, SECTION).or(() -> d.section()).orElseThrow(() -> missing(SECTION));
        Optional<String> version = text(fields, VERSION).or(() -> d.version());
        Optional<URI> originUrl = text(fields, ORIGIN_URL).map(FrontmatterParser::toHttpUri);
        if (source == Source.PUBLIC) {
            version.orElseThrow(() -> missing(VERSION));
            originUrl.orElseThrow(() -> missing(ORIGIN_URL));
        }
        LocalDate collectedAt =
                text(fields, COLLECTED_AT).map(FrontmatterParser::toDate).orElseGet(() -> LocalDate.now(clock));
        boolean reviewed =
                text(fields, REVIEWED).map(FrontmatterParser::toBoolean).orElse(d.reviewed());
        return new DocumentMetadata(
                id, source, language, version, section, aliases(fields), originUrl, collectedAt, reviewed);
    }

    private static DocumentId resolveId(Map<String, Object> fields, Optional<DocumentId> givenId) {
        Optional<DocumentId> fromFrontmatter = text(fields, ID).map(DocumentId::of);
        if (givenId.isPresent() && fromFrontmatter.isPresent() && !givenId.get().equals(fromFrontmatter.get())) {
            throw new InvalidDocumentException("Campo id do frontmatter contradiz o id informado");
        }
        return givenId.or(() -> fromFrontmatter).orElseThrow(() -> missing(ID));
    }

    private static List<String> aliases(Map<String, Object> fields) {
        Object value = fields.get(ALIASES);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> items)) {
            throw new InvalidDocumentException("Campo aliases deve ser uma lista de textos");
        }
        List<String> result = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof String alias)) {
                throw new InvalidDocumentException("Campo aliases deve ser uma lista de textos");
            }
            if (!alias.isBlank()) {
                result.add(alias.strip());
            }
        }
        return result;
    }

    /** Texto do campo, vazio se ausente ou em branco. */
    private static Optional<String> text(Map<String, Object> fields, String key) {
        Object value = fields.get(key);
        if (value == null) {
            return Optional.empty();
        }
        if (!(value instanceof String s)) {
            throw new InvalidDocumentException("Campo " + key + " deve ser um texto simples");
        }
        return s.isBlank() ? Optional.empty() : Optional.of(s.strip());
    }

    private static URI toHttpUri(String value) {
        try {
            URI uri = URI.create(value);
            if (uri.getScheme() == null
                    || !HTTP_SCHEMES.contains(uri.getScheme().toLowerCase(java.util.Locale.ROOT))
                    || uri.getHost() == null
                    || uri.getUserInfo() != null) {
                throw new InvalidDocumentException(
                        "Campo url_origem deve ser uma URL http(s) absoluta, sem usuário e senha");
            }
            return uri;
        } catch (IllegalArgumentException e) {
            if (e instanceof InvalidDocumentException invalid) {
                throw invalid;
            }
            throw new InvalidDocumentException("Campo url_origem deve ser uma URL http(s) absoluta");
        }
    }

    private static LocalDate toDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeException e) {
            throw new InvalidDocumentException("Campo coletado_em deve estar no formato AAAA-MM-DD");
        }
    }

    private static boolean toBoolean(String value) {
        return switch (value) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new InvalidDocumentException("Campo revisado deve ser true ou false");
        };
    }

    private static InvalidDocumentException missing(String field) {
        return new InvalidDocumentException("Campo obrigatório ausente no frontmatter: " + field);
    }

    private static void line(StringBuilder out, String key, String value) {
        out.append(key).append(": ").append(value).append('\n');
    }

    /** Texto YAML entre aspas duplas, com escapes para aspas, barra invertida e caracteres de controle. */
    private static String quote(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < ESCAPE_LIMIT || c == NEXT_LINE || c == LINE_SEPARATOR || c == PARAGRAPH_SEPARATOR) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    /** Resolver sem regras implícitas: todo escalar sem tag explícita é texto. */
    private static final class PlainResolver extends Resolver {

        @Override
        protected void addImplicitResolvers() {
            // intencionalmente vazio
        }
    }
}
