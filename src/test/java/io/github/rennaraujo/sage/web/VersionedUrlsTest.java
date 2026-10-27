package io.github.rennaraujo.sage.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class VersionedUrlsTest {

    private final VersionedUrls urls = VersionedUrls.production();

    private boolean matches(String url, String language, String version) {
        return urls.matches(URI.create(url), language, version);
    }

    @ParameterizedTest
    @CsvSource({
        "https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/stream/Stream.html, 21",
        "https://docs.oracle.com/javase/8/docs/api/java/util/stream/Stream.html, 8",
        "https://docs.oracle.com/javase/specs/jls/se21/html/jls-8.html, 21",
        "https://docs.oracle.com/javase/specs/jvms/se17/html/jvms-17.html, 17",
        "https://openjdk.org/projects/jdk/21/, 21",
    })
    void acceptsJavaPagesWhoseStructuredSegmentIsTheRequestedVersion(String url, String version) {
        assertThat(matches(url, "java", version)).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
        // O número do nome do arquivo não é a versão: jls-8.html está na SE 21.
        "https://docs.oracle.com/javase/specs/jls/se21/html/jls-8.html, 8",
        "https://docs.oracle.com/javase/specs/jls/se21/html/jls-15.html, 15",
        "https://docs.oracle.com/javase/specs/jvms/se21/html/jvms-17.html, 17",
        // JEPs não carregam versão: descartados.
        "https://openjdk.org/jeps/11, 11",
        "https://openjdk.org/jeps/444, 21",
        "https://openjdk.org/jeps/444, 444",
        // Nome de pasta que apenas contém o número.
        "https://docs.oracle.com/javase/Java21Thing/x.html, 21",
        "https://docs.oracle.com/javase/jdk21/docs/x.html, 21",
        // Outra versão no segmento estruturado.
        "https://docs.oracle.com/javase/18/docs/api/x.html, 8",
        "https://docs.oracle.com/en/java/javase/17/docs/api/x.html, 21",
        "https://docs.oracle.com/javase/2021/docs/x.html, 21",
        "https://openjdk.org/projects/jdk/22/, 2",
        // Host sem regra.
        "https://download.oracle.com/javase/21/docs/x.html, 21",
        "https://docs.oracle.com/other/21/x.html, 21",
    })
    void rejectsJavaPagesThatWouldBeMislabelled(String url, String version) {
        assertThat(matches(url, "java", version)).isFalse();
    }

    @Test
    void acceptsSpringPagesOfTheRightProjectAndVersion() {
        assertThat(matches("https://docs.spring.io/spring-boot/3.5/reference/actuator.html", "spring-boot", "3.5"))
                .isTrue();
        assertThat(matches("https://docs.spring.io/spring-boot/docs/3.5.2/reference/html/", "spring-boot", "3.5"))
                .isTrue();
        assertThat(matches("https://docs.spring.io/spring-boot/docs/3.5.x/api/index.html", "spring-boot", "3.5"))
                .isTrue();
        assertThat(matches("https://docs.spring.io/spring-security/reference/6.3/index.html", "spring-security", "6.3"))
                .isTrue();
        assertThat(matches(
                        "https://docs.spring.io/spring-framework/reference/6.1/core.html", "spring-framework", "6.1"))
                .isTrue();
        assertThat(matches(
                        "https://docs.spring.io/spring-framework/docs/6.1.5/reference/html/",
                        "spring-framework",
                        "6.1"))
                .isTrue();
    }

    @Test
    void rejectsSpringPagesOfAnotherVersionProjectOrWithoutVersion() {
        assertThat(matches("https://docs.spring.io/spring-boot/3.4/reference/actuator.html", "spring-boot", "3.5"))
                .isFalse();
        assertThat(matches("https://docs.spring.io/spring-boot/3.50/reference/actuator.html", "spring-boot", "3.5"))
                .isFalse();
        assertThat(matches("https://docs.spring.io/spring-boot/docs/current/reference/html/", "spring-boot", "3.5"))
                .isFalse();
        assertThat(matches("https://docs.spring.io/spring-boot/reference/index.html", "spring-boot", "3.5"))
                .isFalse();
        assertThat(matches("https://docs.spring.io/spring-framework/reference/6.1/core.html", "spring-boot", "6.1"))
                .isFalse();
        assertThat(matches("https://docs.spring.io/spring-boot/3.5/reference/a.html", "spring-security", "3.5"))
                .isFalse();
        assertThat(matches("https://docs.spring.io/spring-boot/3.5/reference/a.html", "java", "3.5"))
                .isFalse();
        assertThat(matches("https://example.com/spring-boot/3.5/reference/a.html", "spring-boot", "3.5"))
                .isFalse();
    }

    @Test
    void dotSegmentsAreResolvedBeforeTheVersionIsChecked() {
        assertThat(matches("https://docs.oracle.com/javase/21/../25/docs/x.html", "java", "21"))
                .isFalse();
        assertThat(matches("https://docs.oracle.com/javase/21/../25/docs/x.html", "java", "25"))
                .isTrue();
        assertThat(matches("https://openjdk.org/projects/jdk/21/../../jeps/444", "java", "21"))
                .isFalse();
        assertThat(matches("https://docs.spring.io/spring-boot/3.5/../3.4/reference/a.html", "spring-boot", "3.5"))
                .isFalse();
        assertThat(matches("https://docs.oracle.com/javase/25/../21/docs/x.html", "java", "21"))
                .isTrue();
    }

    @Test
    void testHostCanBeAliasedToAProductionHost() {
        VersionedUrls aliased = VersionedUrls.production().withAlias("docs.example.test", "docs.oracle.com");

        assertThat(aliased.matches(URI.create("http://docs.example.test:8080/javase/21/docs/a.html"), "java", "21"))
                .isTrue();
        assertThat(aliased.matches(URI.create("http://docs.example.test:8080/javase/17/docs/a.html"), "java", "21"))
                .isFalse();
        assertThat(urls.matches(URI.create("http://docs.example.test:8080/javase/21/docs/a.html"), "java", "21"))
                .isFalse();
    }
}
