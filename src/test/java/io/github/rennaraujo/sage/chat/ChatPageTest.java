package io.github.rennaraujo.sage.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = RANDOM_PORT, properties = "sage.models.dir=target/sem-modelos")
@AutoConfigureTestRestTemplate
class ChatPageTest {

    private static final int MASTER_KEY_BYTES = 32;
    private static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
            + "connect-src 'self'; frame-ancestors 'none'";
    private static final Pattern INLINE_HANDLER = Pattern.compile("\son[a-z]+\s*=", Pattern.CASE_INSENSITIVE);

    @TempDir
    static Path storageDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        byte[] master = new byte[MASTER_KEY_BYTES];
        new SecureRandom().nextBytes(master);
        Path keyFile = Files.createTempFile("sage-test-master", ".key");
        keyFile.toFile().deleteOnExit();
        Files.writeString(keyFile, Base64.getEncoder().encodeToString(master));
        registry.add("sage.crypto.provider", () -> "local");
        registry.add("sage.crypto.local-key-file", keyFile::toString);
        registry.add("sage.storage.type", () -> "local");
        registry.add("sage.storage.local-dir", storageDir::toString);
    }

    @Autowired
    TestRestTemplate rest;

    @Test
    void chatPageServedWithCsp() {
        ResponseEntity<String> response = rest.getForEntity("/chat/", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst("Content-Security-Policy")).isEqualTo(CSP);
        assertThat(response.getHeaders().getContentType().toString()).startsWith("text/html");
        assertThat(response.getBody()).contains("<title>").contains("chat.js").contains("chat.css");
    }

    @Test
    void chatAssetsAreServedWithoutKey() {
        assertThat(rest.getForEntity("/chat/chat.js", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        ResponseEntity<String> css = rest.getForEntity("/chat/chat.css", String.class);
        assertThat(css.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(css.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE)).startsWith("text/css");
    }

    @Test
    void chatWithoutSlashRedirectsToSlash() {
        ResponseEntity<String> response = rest.getForEntity("/chat", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("chat.js");
    }

    @Test
    void noExternalScripts() throws IOException {
        String html = resource("index.html");

        assertThat(html).doesNotContain("<script src=\"http").doesNotContain("<script src=\"//");
        assertThat(html).doesNotContainPattern("(?i)<script(?![^>]*\ssrc=)[^>]*>");
        assertThat(html)
                .doesNotContain("href=\"http")
                .doesNotContain("src=\"http")
                .doesNotContain("<style");
        assertThat(html).doesNotContain(" style=");
        assertThat(INLINE_HANDLER.matcher(html).find()).isFalse();
    }

    @Test
    void chatJsNeverUsesInnerHtml() throws IOException {
        String js = resource("chat.js");

        assertThat(js)
                .doesNotContain("innerHTML")
                .doesNotContain("outerHTML")
                .doesNotContain("insertAdjacentHTML")
                .doesNotContain("document.write")
                .doesNotContain("eval(")
                .doesNotContain("new Function")
                .doesNotContain("localStorage");
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = ChatPageTest.class.getResourceAsStream("/static/chat/" + name)) {
            assertThat(in).as(name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
