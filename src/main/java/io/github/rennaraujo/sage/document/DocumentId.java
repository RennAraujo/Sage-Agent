package io.github.rennaraujo.sage.document;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Identificador lógico do documento, no formato de caminho: {@code publico/<linguagem>/<versao>/<slug>.md} ou
 * {@code interno/<app>/<slug>.md}. Só existe dentro do payload cifrado; nunca vira chave de blob.
 */
public record DocumentId(String value) {

    private static final String PUBLIC_PREFIX = "publico";
    private static final String INTERNAL_PREFIX = "interno";
    private static final int PUBLIC_SEGMENTS = 4;
    private static final int INTERNAL_SEGMENTS = 3;
    private static final int LOG_REF_BYTES = 6;
    private static final int MAX_LENGTH = 200;
    private static final Pattern SEGMENT = Pattern.compile("[a-z0-9][a-z0-9._-]*");
    private static final Pattern FILE = Pattern.compile("[a-z0-9][a-z0-9._-]*\\.md");

    public DocumentId {
        if (value == null || value.length() > MAX_LENGTH || !isValid(value)) {
            throw new InvalidDocumentException("Id de documento inválido: use publico/<linguagem>/<versao>/<slug>.md "
                    + "ou interno/<app>/<slug>.md, com segmentos [a-z0-9][a-z0-9._-]*");
        }
    }

    /** Normaliza para minúsculas e valida. */
    public static DocumentId of(String raw) {
        if (raw == null) {
            throw new InvalidDocumentException("Id de documento ausente");
        }
        return new DocumentId(raw.toLowerCase(Locale.ROOT));
    }

    /** Referência curta e estável para logs: 12 hex do SHA-256 do id. O id em si nunca vai para log. */
    public String logRef() {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, LOG_REF_BYTES);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível", e);
        }
    }

    public Source source() {
        return value.startsWith(PUBLIC_PREFIX + "/") ? Source.PUBLIC : Source.INTERNAL;
    }

    private static boolean isValid(String id) {
        String[] segments = id.split("/", -1);
        int expected;
        if (PUBLIC_PREFIX.equals(segments[0])) {
            expected = PUBLIC_SEGMENTS;
        } else if (INTERNAL_PREFIX.equals(segments[0])) {
            expected = INTERNAL_SEGMENTS;
        } else {
            return false;
        }
        if (segments.length != expected) {
            return false;
        }
        for (int i = 1; i < segments.length - 1; i++) {
            if (!SEGMENT.matcher(segments[i]).matches()) {
                return false;
            }
        }
        return FILE.matcher(segments[segments.length - 1]).matches();
    }

    @Override
    public String toString() {
        return "DocumentId[" + logRef() + "]";
    }
}
