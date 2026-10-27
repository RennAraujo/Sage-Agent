package io.github.rennaraujo.sage.web;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * Fallback web. {@code allowlist} liga o id da linguagem (o mesmo de {@code aliases-publicos.yml}) aos domínios
 * oficiais de onde se aceita buscar páginas; um host vale se for igual ao domínio ou subdomínio dele. A chave do Brave
 * vem do ambiente e nunca aparece em {@code toString}.
 */
@Validated
@ConfigurationProperties("sage.web")
public record WebProperties(
        @NotEmpty Map<String, Set<String>> allowlist,
        @Min(1) @DefaultValue("3") int maxPagesPerQuestion,
        @NotNull @DefaultValue("5s") Duration fetchTimeout,
        @NotNull @DefaultValue("2MB") DataSize maxPageBytes,
        @Min(0) @DefaultValue("3") int maxRedirects,
        @DefaultValue("") String braveApiKey,

        @NotNull @DefaultValue("https://api.search.brave.com/res/v1/web/search")
        URI braveEndpoint,

        @NotNull @DefaultValue("8s") Duration fallbackTimeout) {

    /** Nome de host com ao menos um ponto, rótulos alfanuméricos (hífen no meio) e TLD de letras. */
    private static final Pattern DOMAIN =
            Pattern.compile("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*\\.[a-z]{2,}$");

    /** Prazo padrão do fallback inteiro (busca e páginas), para quem monta propriedades à mão. */
    private static final Duration DEFAULT_FALLBACK_TIMEOUT = Duration.ofSeconds(8);

    public WebProperties(
            Map<String, Set<String>> allowlist,
            int maxPagesPerQuestion,
            Duration fetchTimeout,
            DataSize maxPageBytes,
            int maxRedirects,
            String braveApiKey,
            URI braveEndpoint) {
        this(
                allowlist,
                maxPagesPerQuestion,
                fetchTimeout,
                maxPageBytes,
                maxRedirects,
                braveApiKey,
                braveEndpoint,
                DEFAULT_FALLBACK_TIMEOUT);
    }

    @ConstructorBinding
    public WebProperties {
        Map<String, Set<String>> normalized = new LinkedHashMap<>();
        if (allowlist != null) {
            allowlist.forEach((language, domains) -> normalized.put(
                    language.toLowerCase(Locale.ROOT),
                    domains.stream()
                            .map(d -> d.strip().toLowerCase(Locale.ROOT))
                            .collect(Collectors.toUnmodifiableSet())));
        }
        allowlist = Map.copyOf(normalized);
        braveApiKey = braveApiKey == null ? "" : braveApiKey.strip();
    }

    @AssertTrue(message = "sage.web.fetch-timeout e sage.web.max-page-bytes devem ser maiores que zero")
    public boolean isLimitsPositive() {
        return fetchTimeout != null
                && !fetchTimeout.isNegative()
                && !fetchTimeout.isZero()
                && maxPageBytes != null
                && maxPageBytes.toBytes() > 0;
    }

    @AssertTrue(
            message = "sage.web.allowlist: cada domínio precisa ter ao menos um ponto, sem ponto no começo ou no fim")
    public boolean isAllowlistWellFormed() {
        return allowlist.values().stream()
                .flatMap(Set::stream)
                .allMatch(d -> DOMAIN.matcher(d).matches());
    }

    /** Domínios aceitos para a linguagem; vazio se ela não tem fontes oficiais configuradas. */
    public Set<String> domainsFor(String language) {
        return allowlist.getOrDefault(language.toLowerCase(Locale.ROOT), Set.of());
    }

    public boolean hasBraveKey() {
        return !braveApiKey.isBlank();
    }

    @Override
    public String toString() {
        return "WebProperties[languages=" + allowlist.keySet() + ", braveApiKey=" + (hasBraveKey() ? "***" : "") + "]";
    }
}
