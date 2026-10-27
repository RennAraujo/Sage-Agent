package io.github.rennaraujo.sage.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.HashSet;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Chaves de API aceitas. Só o hash SHA-256 (hex) de cada chave fica na configuração; a chave em si nunca é guardada.
 * A validação do hash é feita por {@code @AssertTrue} (e não por {@code @Pattern}) para que a mensagem de erro não
 * repita o valor recebido: quem colar a chave crua no campo errado não a vê escrita no log de subida.
 */
@Validated
@ConfigurationProperties("sage.auth")
public record ApiKeyProperties(@Valid @DefaultValue List<Key> keys) {

    private static final java.util.regex.Pattern SHA256_HEX = java.util.regex.Pattern.compile("[0-9a-fA-F]{64}");

    public record Key(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_.-]{1,64}")
            String name,

            @NotNull Role role,
            String sha256) {}

    @AssertTrue(
            message = "sage.auth.keys[].sha256 deve ter 64 caracteres hexadecimais (o SHA-256 da chave, não a chave)")
    public boolean isEveryHashWellFormed() {
        return keys.stream()
                .allMatch(key ->
                        key.sha256() != null && SHA256_HEX.matcher(key.sha256()).matches());
    }

    @AssertTrue(message = "sage.auth.keys[].sha256 não pode se repetir entre chaves")
    public boolean isEveryHashUnique() {
        HashSet<String> seen = new HashSet<>();
        return keys.stream()
                .map(Key::sha256)
                .filter(java.util.Objects::nonNull)
                .map(hash -> hash.toLowerCase(java.util.Locale.ROOT))
                .allMatch(seen::add);
    }

    @AssertTrue(message = "sage.auth.keys[].name não pode se repetir")
    public boolean isEveryNameUnique() {
        HashSet<String> seen = new HashSet<>();
        return keys.stream().map(Key::name).allMatch(seen::add);
    }
}
