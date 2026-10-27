package io.github.rennaraujo.sage.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Os exemplos são montados por concatenação para que nenhum literal do arquivo pareça uma credencial de verdade para
 * varreduras de segredo do repositório.
 */
class SecretScannerTest {

    private static final int GITHUB_TOKEN_CHARS = 36;
    private static final int SAGE_KEY_CHARS = 40;
    private static final int ONE_MB = 1024 * 1024;

    private final SecretScanner scanner = new SecretScanner();

    static Stream<Arguments> positives() {
        return Stream.of(
                Arguments.of("aws-access-key", "AKIA" + "IOSFODNN7EXAMPLE"),
                Arguments.of("private-key", "-----BEGIN " + "RSA PRIVATE KEY-----"),
                Arguments.of("private-key", "-----BEGIN " + "PRIVATE KEY-----"),
                Arguments.of("github-token", "gh" + "p_" + "a".repeat(GITHUB_TOKEN_CHARS)),
                Arguments.of("jwt", "ey" + "Jhbgc.abc_def-1.xyz_123-9"),
                Arguments.of("slack-token", "xo" + "xb-1234567890-abcdefghij"),
                Arguments.of("sage-api-key", "sa" + "ge_" + "A1".repeat(SAGE_KEY_CHARS / 2)),
                Arguments.of("password-assignment", "password: hunter2hunter2"),
                Arguments.of("password-assignment", "SENHA = abcdefgh123"),
                Arguments.of("password-assignment", "api_token:    abcdefghij"),
                Arguments.of("password-assignment", "Secret=12345678"));
    }

    static Stream<Arguments> negatives() {
        return Stream.of(
                Arguments.of("aws-access-key", "AKIA" + "SHORT"),
                Arguments.of("aws-access-key", "akia" + "iosfodnn7example1"),
                Arguments.of("private-key", "-----BEGIN " + "CERTIFICATE-----"),
                Arguments.of("github-token", "gh" + "p_" + "a".repeat(GITHUB_TOKEN_CHARS - 1)),
                Arguments.of("github-token", "gh" + "x_" + "a".repeat(GITHUB_TOKEN_CHARS)),
                Arguments.of("jwt", "ey" + "Jhbgc.sem-terceiro-segmento"),
                Arguments.of("slack-token", "xo" + "xb-curto"),
                Arguments.of("slack-token", "xo" + "xz-1234567890-abcdefghij"),
                Arguments.of("sage-api-key", "sa" + "ge_" + "a".repeat(SAGE_KEY_CHARS - 1)),
                Arguments.of("password-assignment", "a senha do usuário é definida no painel"),
                Arguments.of("password-assignment", "password: curta"),
                Arguments.of("password-assignment", "token do cliente"));
    }

    @ParameterizedTest(name = "{0} detecta {1}")
    @MethodSource("positives")
    void detectsEachPattern(String type, String sample) {
        assertThat(scanner.scan("texto antes\n" + sample + "\ndepois")).contains(new SecretFinding(type, 2));
    }

    @ParameterizedTest(name = "{0} ignora {1}")
    @MethodSource("negatives")
    void ignoresNearMisses(String type, String sample) {
        assertThat(scanner.scan("texto antes\n" + sample + "\ndepois"))
                .noneMatch(finding -> finding.type().equals(type));
    }

    static Stream<String> adversarialUnits() {
        return Stream.of(
                "ey" + "J",
                "ey" + "Ja.",
                "ey" + "Ja.b",
                "-----BEGIN ",
                "-----BEGIN A ",
                "AKIA",
                "gh" + "p_",
                "xo" + "xb-",
                "sa" + "ge_",
                "token",
                "token ",
                "password:",
                "senha: ");
    }

    @ParameterizedTest(name = "linha adversária de 1 MB com {0}")
    @MethodSource("adversarialUnits")
    void scanTimeIsLinearOnAdversarialLines(String unit) {
        String line = unit.repeat(ONE_MB / unit.length());

        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> scanner.scan(line));
    }

    @Test
    void jwtInsideALongerWordIsNotAJwtButRealOnesAreStillFound() {
        assertThat(scanner.scan("x" + "ey" + "Jabc.def.ghi")).isEmpty();
        assertThat(scanner.scan("Authorization: " + "ey" + "Jabc.def.ghi")).contains(new SecretFinding("jwt", 1));
    }

    @Test
    void reportsTheRightLineForEachOccurrenceWithAnyLineBreak() {
        String text = "um\r\ndois\nAKIA" + "IOSFODNN7EXAMPLE\rquatro\n-----BEGIN " + "EC PRIVATE KEY-----";

        List<SecretFinding> findings = scanner.scan(text);

        assertThat(findings)
                .containsExactly(new SecretFinding("aws-access-key", 3), new SecretFinding("private-key", 5));
    }

    @Test
    void cleanTextHasNoFindingsAndFindingsNeverCarryTheValue() {
        assertThat(scanner.scan("# Guia\n\nTexto comum sobre faturamento.\n")).isEmpty();

        String secret = "AKIA" + "IOSFODNN7EXAMPLE";
        SecretFinding finding = scanner.scan(secret).getFirst();
        assertThat(finding.toString()).doesNotContain(secret);
    }
}
