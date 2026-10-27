package io.github.rennaraujo.sage.crypto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("sage.crypto")
public record CryptoProperties(
        @NotNull @DefaultValue("local") Provider provider,
        @NotNull @DefaultValue("secrets/local-master.key") Path localKeyFile,
        @DefaultValue("") String kmsKeyId) {

    public enum Provider {
        LOCAL,
        KMS
    }

    @AssertTrue(message = "sage.crypto.kms-key-id é obrigatório quando sage.crypto.provider=kms")
    public boolean isKmsKeyIdPresentWhenKms() {
        return provider != Provider.KMS || !kmsKeyId.isBlank();
    }
}
