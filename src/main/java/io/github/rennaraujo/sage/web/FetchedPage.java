package io.github.rennaraujo.sage.web;

import java.net.URI;

/** Página baixada: a URL final (depois dos redirects) e o HTML ainda cru. */
public record FetchedPage(URI url, String html) {

    @Override
    public String toString() {
        return "FetchedPage[url=" + url.getHost() + "]";
    }
}
