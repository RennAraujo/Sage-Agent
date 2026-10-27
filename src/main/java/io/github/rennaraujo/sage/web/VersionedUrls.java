package io.github.rennaraujo.sage.web;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Diz se uma URL é, de fato, da versão pedida. O fallback grava a página com o rótulo da versão da pergunta; se for
 * de outra versão, o Sage passaria a afirmar a versão errada, o erro que o projeto mais quer evitar. Por isso a
 * versão não é procurada "em qualquer lugar do caminho" (o {@code 8} de {@code jls-8.html} não é Java 8, e um JEP não
 * diz de que versão é): cada domínio oficial tem uma estrutura de caminho conhecida, e o segmento de versão dessa
 * estrutura precisa ser exatamente o pedido.
 *
 * <ul>
 *   <li>{@code docs.oracle.com}: {@code /[en/][java/]javase/<v>/...} e {@code /javase/specs/(jls|jvms)/se<v>/...}
 *   <li>{@code openjdk.org}: {@code /projects/jdk/<v>/...}
 *   <li>{@code docs.spring.io}: {@code /spring-(boot|framework|security)/[docs|reference|api/]<maior.menor>/...},
 *       com o projeto igual ao da linguagem
 * </ul>
 *
 * Qualquer outra URL (JEPs, {@code /current/}, hosts sem regra, subdomínios) é descartada: sem estrutura conhecida não
 * há como saber a versão.
 */
public final class VersionedUrls {

    private static final String ORACLE = "docs.oracle.com";
    private static final String OPENJDK = "openjdk.org";
    private static final String SPRING = "docs.spring.io";

    private static final Pattern ORACLE_JAVASE =
            Pattern.compile("^/(?:[a-z]{2}(?:-[a-z]{2})?/)?(?:java/)?javase/(\\d+)(?:/|$)");
    private static final Pattern ORACLE_SPECS = Pattern.compile("^/javase/specs/(?:jls|jvms)/se(\\d+)(?:/|$)");
    private static final Pattern OPENJDK_PROJECT = Pattern.compile("^/projects/jdk/(\\d+)(?:/|$)");
    private static final Pattern SPRING_PROJECT = Pattern.compile(
            "^/spring-(boot|framework|security)/(?:(?:docs|reference|api)/)?(\\d+\\.\\d+)(?:\\.(?:\\d+|x))*"
                    + "(?:\\.[A-Za-z]+)?(?:/|$)");

    /** Host (minúsculo) para o domínio de referência cujas regras ele segue. */
    private final Map<String, String> rulesOfHost;

    private VersionedUrls(Map<String, String> rulesOfHost) {
        this.rulesOfHost = Map.copyOf(rulesOfHost);
    }

    public static VersionedUrls production() {
        return new VersionedUrls(Map.of(ORACLE, ORACLE, OPENJDK, OPENJDK, SPRING, SPRING));
    }

    /** Para testes: faz {@code host} seguir as regras de {@code like} (por exemplo, um host local do WireMock). */
    VersionedUrls withAlias(String host, String like) {
        Map<String, String> copy = new HashMap<>(rulesOfHost);
        copy.put(host.toLowerCase(Locale.ROOT), rulesOfHost.get(like));
        return new VersionedUrls(copy);
    }

    boolean matches(URI raw, String language, String version) {
        // Segmentos "." e ".." são resolvidos antes: /javase/21/../25/x é uma página do 25.
        URI url = raw.normalize();
        String host = url.getHost() == null ? "" : url.getHost().toLowerCase(Locale.ROOT);
        String rules = rulesOfHost.get(host);
        String path = url.getPath() == null ? "" : url.getPath();
        if (rules == null) {
            return false;
        }
        return switch (rules) {
            case ORACLE -> "java".equals(language) && anyEquals(List.of(ORACLE_JAVASE, ORACLE_SPECS), path, version);
            case OPENJDK -> "java".equals(language) && anyEquals(List.of(OPENJDK_PROJECT), path, version);
            case SPRING -> springMatches(path, language, version);
            default -> false;
        };
    }

    private static boolean anyEquals(List<Pattern> patterns, String path, String version) {
        for (Pattern pattern : patterns) {
            Matcher m = pattern.matcher(path);
            if (m.find() && m.group(1).equals(version)) {
                return true;
            }
        }
        return false;
    }

    private static boolean springMatches(String path, String language, String version) {
        Matcher m = SPRING_PROJECT.matcher(path);
        return m.find()
                && ("spring-" + m.group(1)).equals(language)
                && m.group(2).equals(version);
    }
}
