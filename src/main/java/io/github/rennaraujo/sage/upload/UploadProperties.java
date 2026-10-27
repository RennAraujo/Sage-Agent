package io.github.rennaraujo.sage.upload;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * Limites do upload. {@code maxFileBytes} vale para o arquivo enviado e também para o Markdown já convertido (um PDF
 * pequeno pode expandir). {@code maxZipBytes} é o tamanho do zip em si; as outras duas travam zip bomb.
 */
@Validated
@ConfigurationProperties("sage.upload")
public record UploadProperties(
        @NotNull @DefaultValue("10MB") DataSize maxFileBytes,
        @NotNull @DefaultValue("50MB") DataSize maxZipBytes,
        @Min(1) @DefaultValue("500") int maxZipEntries,
        @NotNull @DefaultValue("200MB") DataSize maxZipUncompressedBytes) {

    @AssertTrue(message = "sage.upload.max-* devem ser tamanhos maiores que zero")
    public boolean isEverySizePositive() {
        return maxFileBytes != null
                && maxFileBytes.toBytes() > 0
                && maxZipBytes != null
                && maxZipBytes.toBytes() > 0
                && maxZipUncompressedBytes != null
                && maxZipUncompressedBytes.toBytes() > 0;
    }
}
