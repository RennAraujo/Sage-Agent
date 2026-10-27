package io.github.rennaraujo.sage.question;

/** Caminho que a pergunta segue. FAST_MODEL, STRONG_MODEL e NOT_DOCUMENTED estão em uso; os outros vêm depois. */
public enum Route {
    CACHE,
    FAST_MODEL,
    STRONG_MODEL,
    FLOW,
    WEB_FALLBACK,
    NOT_DOCUMENTED
}
