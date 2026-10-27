package io.github.rennaraujo.sage.upload;

import java.util.List;
import java.util.Optional;

/**
 * O documento tem cara de conter credenciais (vira 422). Carrega só tipos e linhas; {@code file} é o caminho da
 * entrada dentro do zip, quando o envio foi um zip.
 */
public class SecretsFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient List<SecretFinding> findings;
    private final transient Optional<String> file;

    public SecretsFoundException(List<SecretFinding> findings, Optional<String> file) {
        super("O documento parece conter segredos");
        this.findings = List.copyOf(findings);
        this.file = file;
    }

    public List<SecretFinding> findings() {
        return findings;
    }

    public Optional<String> file() {
        return file;
    }
}
