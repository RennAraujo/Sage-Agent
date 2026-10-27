package io.github.rennaraujo.sage.upload;

import java.util.Optional;

/**
 * Um envio: o arquivo e os campos opcionais do formulário, usados quando o documento não traz frontmatter completo.
 * {@code replace} autoriza sobrescrever documentos que já existem. {@code originUrl} é necessário para documentos
 * públicos sem frontmatter (o frontmatter exige {@code url_origem}).
 */
public record UploadRequest(
        String filename,
        byte[] content,
        Optional<String> source,
        Optional<String> language,
        Optional<String> version,
        Optional<String> section,
        Optional<String> originUrl,
        boolean replace) {

    /** O conteúdo do arquivo fica fora do toString para não vazar em log. */
    @Override
    public String toString() {
        return "UploadRequest[" + content.length + " bytes]";
    }
}
