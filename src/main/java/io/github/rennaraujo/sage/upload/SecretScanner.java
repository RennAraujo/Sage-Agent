package io.github.rennaraujo.sage.upload;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Procura credenciais conhecidas no texto antes de qualquer gravação. Devolve só o tipo e a linha de cada achado:
 * o valor encontrado não sai daqui, para não parar em log nem em resposta de erro. Os padrões são deliberadamente
 * simples e favorecem recusar demais (um {@code senha: ${SENHA_DO_BANCO}} também é recusado) a deixar passar. O
 * padrão do JWT exige que "eyJ" não venha colado a outro caractere de palavra: sem isso, uma linha de "eyJ" repetido
 * custa tempo quadrático (13 s com 120 KB), o que seria um jeito barato de prender uma thread.
 */
public final class SecretScanner {

    private static final List<Rule> RULES = List.of(
            rule("aws-access-key", "AKIA[0-9A-Z]{16}"),
            rule("private-key", "-----BEGIN [A-Z ]*PRIVATE KEY-----"),
            rule("github-token", "gh[pousr]_[A-Za-z0-9]{36}"),
            rule("jwt", "(?<![\\w-])eyJ[\\w-]+\\.[\\w-]+\\.[\\w-]+"),
            rule("slack-token", "xox[baprs]-[A-Za-z0-9-]{10,}"),
            rule("sage-api-key", "sage_[A-Za-z0-9]{40,}"),
            rule("password-assignment", "(?i)(password|senha|secret|token)\\s*[:=]\\s*\\S{8,}"));

    private static final Pattern LINE_BREAK = Pattern.compile("\r\n|\n|\r");

    public List<SecretFinding> scan(String text) {
        List<SecretFinding> findings = new ArrayList<>();
        String[] lines = LINE_BREAK.split(text, -1);
        for (int i = 0; i < lines.length; i++) {
            for (Rule rule : RULES) {
                if (rule.pattern().matcher(lines[i]).find()) {
                    findings.add(new SecretFinding(rule.type(), i + 1));
                }
            }
        }
        return List.copyOf(findings);
    }

    private static Rule rule(String type, String regex) {
        return new Rule(type, Pattern.compile(regex));
    }

    private record Rule(String type, Pattern pattern) {}
}
