package io.github.rennaraujo.sage.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rennaraujo.sage.testsupport.ScratchDirs;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Import;

class CryptoConfigTest {

    private final Path dir = ScratchDirs.create("sage-teste-");

    @AfterEach
    void deleteScratchDir() {
        ScratchDirs.delete(dir);
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(Config.class);

    @Test
    void startupFailsWhenLocalKeyFileIsMissing() {
        runner.withPropertyValues("sage.crypto.local-key-file=" + dir.resolve("nao-existe.key"))
                .run(context ->
                        assertThat(context).hasFailed().getFailure().hasStackTraceContaining("generate-local-key"));
    }

    @Test
    void startupFailsWhenKmsProviderHasNoKeyId() {
        // O KmsClient é criado antes de a validação rodar; a região fixa evita falhar por outro motivo.
        runner.withSystemProperties("aws.region=us-east-1")
                .withPropertyValues("sage.crypto.provider=kms")
                .run(context -> assertThat(context).hasFailed().getFailure().hasStackTraceContaining("kms-key-id"));
    }

    @EnableConfigurationProperties
    @Import(CryptoConfig.class)
    static class Config {}
}
