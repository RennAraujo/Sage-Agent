package io.github.rennaraujo.sage.web;

import java.util.Locale;
import java.util.Set;

/** Regra única de "este host pertence à allowlist": igual ao domínio ou subdomínio dele, nunca sufixo solto. */
final class AllowedHosts {

    private AllowedHosts() {}

    static boolean matches(String host, Set<String> domains) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        if (h.endsWith(".")) {
            h = h.substring(0, h.length() - 1);
        }
        for (String domain : domains) {
            if (h.equals(domain) || h.endsWith("." + domain)) {
                return true;
            }
        }
        return false;
    }
}
