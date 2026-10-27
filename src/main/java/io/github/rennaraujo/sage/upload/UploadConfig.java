package io.github.rennaraujo.sage.upload;

import io.github.rennaraujo.sage.audit.AuditLog;
import io.github.rennaraujo.sage.auth.RateLimiter;
import io.github.rennaraujo.sage.document.DocumentStore;
import io.github.rennaraujo.sage.document.FrontmatterParser;
import jakarta.servlet.MultipartConfigElement;
import java.time.Clock;
import java.util.Objects;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.servlet.autoconfigure.MultipartProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(UploadProperties.class)
class UploadConfig {

    private static final long FORM_SLACK_BYTES = 1024L * 1024L;

    /**
     * Configuração do multipart derivada dos limites do upload. O limite do arquivo é o do zip e o da requisição ganha
     * uma folga para os campos do formulário. O limiar de gravação em disco é o teto da requisição: o padrão do Spring
     * é 0, e com ele o Tomcat grava cada parte, em claro, em upload_*.tmp no diretório temporário.
     */
    @Bean
    MultipartConfigElement multipartConfigElement(MultipartProperties multipart, UploadProperties upload) {
        long maxFile = upload.maxZipBytes().toBytes();
        long maxRequest = maxFile + FORM_SLACK_BYTES;
        return new MultipartConfigElement(Objects.toString(multipart.getLocation(), ""), maxFile, maxRequest, (int)
                Math.min(maxRequest, Integer.MAX_VALUE));
    }

    @Bean
    SecretScanner secretScanner() {
        return new SecretScanner();
    }

    @Bean
    DocumentConverter documentConverter(UploadProperties properties) {
        return new DocumentConverter(properties);
    }

    @Bean
    ZipExtractor zipExtractor(UploadProperties properties) {
        return new ZipExtractor(properties);
    }

    @Bean
    UploadService uploadService(
            DocumentStore store,
            FrontmatterParser parser,
            DocumentConverter converter,
            ZipExtractor zipExtractor,
            SecretScanner scanner,
            AuditLog auditLog,
            RateLimiter rateLimiter,
            UploadProperties properties) {
        return new UploadService(
                store, parser, converter, zipExtractor, scanner, auditLog, rateLimiter, properties, Clock.systemUTC());
    }
}
