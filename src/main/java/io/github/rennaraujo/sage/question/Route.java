package io.github.rennaraujo.sage.question;

/** Caminho que a pergunta segue. Por enquanto só FAST_MODEL e NOT_DOCUMENTED são usados; os outros vêm depois. */
public enum Route {
    CACHE,
    FAST_MODEL,
    STRONG_MODEL,
    FLOW,
    WEB_FALLBACK,
    NOT_DOCUMENTED
}
