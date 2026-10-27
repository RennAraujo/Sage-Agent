package io.github.rennaraujo.sage.document;

import java.util.Objects;

public record Document(DocumentMetadata metadata, String body) {

    public Document {
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(body, "body");
        // O parser normaliza CRLF/CR para LF; normalizar aqui garante que render -> parse devolva um Document igual.
        body = body.replace("\r\n", "\n").replace('\r', '\n');
    }

    /** O corpo é conteúdo do acervo: fica fora do toString para não vazar em log. */
    @Override
    public String toString() {
        return "Document[" + metadata.id() + "]";
    }
}
