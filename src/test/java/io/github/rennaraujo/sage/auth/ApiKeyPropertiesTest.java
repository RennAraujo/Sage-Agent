package io.github.rennaraujo.sage.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;

class ApiKeyPropertiesTest {

    private static final String VALID_HASH = "a".repeat(64);
    private static final String RAW_KEY = "sage_" + "Z".repeat(40);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(Config.class);

    @Test
    void acceptsWellFormedKeys() {
        runner.withPropertyValues(
                        "sage.auth.keys[0].name=ana",
                        "sage.auth.keys[0].role=EDITOR",
                        "sage.auth.keys[0].sha256=" + VALID_HASH.toUpperCase(Locale.ROOT))
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void startsWithNoKeysConfigured() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ApiKeyProperties.class).keys()).isEmpty();
        });
    }

    @Test
    void rejectsHashThatIsNotSha256HexAndNeverEchoesTheValue() {
        runner.withPropertyValues(
                        "sage.auth.keys[0].name=ana",
                        "sage.auth.keys[0].role=READER",
                        "sage.auth.keys[0].sha256=" + RAW_KEY)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("sha256");
                    assertThat(messages(context.getStartupFailure())).doesNotContain(RAW_KEY);
                });
    }

    @Test
    void rejectsDuplicateNamesAndMissingRole() {
        runner.withPropertyValues(
                        "sage.auth.keys[0].name=ana",
                        "sage.auth.keys[0].role=READER",
                        "sage.auth.keys[0].sha256=" + VALID_HASH,
                        "sage.auth.keys[1].name=ana",
                        "sage.auth.keys[1].role=EDITOR",
                        "sage.auth.keys[1].sha256=" + "b".repeat(64))
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("sage.auth.keys[0].name=ana", "sage.auth.keys[0].sha256=" + VALID_HASH)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsDuplicateHashesWithoutEchoingThem() {
        String hash = "c".repeat(64);
        runner.withPropertyValues(
                        "sage.auth.keys[0].name=ana",
                        "sage.auth.keys[0].role=READER",
                        "sage.auth.keys[0].sha256=" + hash,
                        "sage.auth.keys[1].name=beto",
                        "sage.auth.keys[1].role=EDITOR",
                        "sage.auth.keys[1].sha256=" + hash.toUpperCase(Locale.ROOT))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("sha256");
                    assertThat(messages(context.getStartupFailure()).toLowerCase(Locale.ROOT))
                            .doesNotContain(hash);
                });
    }

    private static String messages(Throwable failure) {
        StringBuilder text = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            text.append(t.getMessage()).append('\n');
        }
        return text.toString();
    }

    @EnableConfigurationProperties(ApiKeyProperties.class)
    static class Config {}
}
