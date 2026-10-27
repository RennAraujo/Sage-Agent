package io.github.rennaraujo.sage.question;

import io.github.rennaraujo.sage.document.Source;
import java.util.Objects;
import java.util.Optional;

/**
 * Uma fonte citada pela resposta, na mesma regra da linha "Documentação consultada": só entra quem foi citado por uma
 * frase aprovada, na ordem da primeira citação. {@code language} é o id da linguagem pública ou o nome da aplicação
 * interna; {@code name} é o nome legível ("Java", "Spring Boot"; na interna, o próprio nome da aplicação).
 */
public record ConsultedSource(String language, String name, Optional<String> version, Source source) {

    public ConsultedSource {
        Objects.requireNonNull(language, "language");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(source, "source");
    }

    /** Trecho público sem versão: o texto mostra "(versão não informada)" e o JSON, versão nula com esta marca. */
    public boolean versionUnknown() {
        return source == Source.PUBLIC && version.isEmpty();
    }
}
