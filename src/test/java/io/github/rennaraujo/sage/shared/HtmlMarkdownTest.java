package io.github.rennaraujo.sage.shared;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HtmlMarkdownTest {

    @Test
    void stripsInvisibleFormatCharactersFromTheMarkdown() {
        String md = HtmlMarkdown.fromHtml("<h1>Ti​tulo</h1><p>a‮b﻿c</p>");

        assertThat(md).contains("# Titulo").contains("abc");
    }

    @Test
    void keepsAccentsAndRegularText() {
        assertThat(HtmlMarkdown.fromHtml("<p>ação e coração</p>")).contains("ação e coração");
    }
}
