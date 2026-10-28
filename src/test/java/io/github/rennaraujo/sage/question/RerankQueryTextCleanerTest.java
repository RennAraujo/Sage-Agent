package io.github.rennaraujo.sage.question;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RerankQueryTextCleanerTest {

    private final RerankQueryTextCleaner cleaner =
            new RerankQueryTextCleaner(new AliasCatalog(new FakeCatalogs.Store()));

    @Test
    void removesVersionsAndLanguageNamesAndKeepsTheTopic() {
        assertThat(cleaner.clean("sealed classes existe no java 21 e no java 25?"))
                .isEqualTo("sealed classes?");
        assertThat(cleaner.clean("Como criar uma virtual thread no java 21?"))
                .isEqualTo("Como criar uma virtual thread?");
        assertThat(cleaner.clean("traga a documentação do java 21 sobre sequenced collections"))
                .doesNotContainIgnoringCase("java")
                .doesNotContain("21")
                .contains("sequenced collections");
    }

    @Test
    void removesChainedVersionsAndComparisonPhrases() {
        assertThat(cleaner.clean("o @MockBean existe no spring boot 4.1 ou é exclusivo do spring boot 3.5?"))
                .isEqualTo("@MockBean?")
                .doesNotContain("4.1");
        assertThat(cleaner.clean("diferença do spring boot 3.5 e 4.1 para actuator"))
                .contains("actuator")
                .doesNotContain("3.5");
    }

    @Test
    void keepsTheOriginalWhenNothingIsLeft() {
        assertThat(cleaner.clean("java 21")).isEqualTo("java 21");
    }

    @Test
    void leavesQuestionsWithoutVersionOrLanguageAlone() {
        assertThat(cleaner.clean("Qual o timeout das chamadas ao gateway de pagamento?"))
                .isEqualTo("Qual o timeout das chamadas ao gateway de pagamento?");
    }

    @Test
    void numbersThatAreNotVersionsSurvive() {
        assertThat(cleaner.clean("java 21 e 3 exemplos de records")).contains("3 exemplos");
        assertThat(cleaner.clean("Quais os 3 passos para criar uma thread no java 21, 2 por vez?"))
                .contains("3 passos")
                .contains("2 por vez");
    }

    @Test
    void chainedVersionsRightAfterAVersionAreStripped() {
        assertThat(cleaner.clean("records existe no java 17, 21 e 25?"))
                .doesNotContain("21")
                .doesNotContain("25");
    }
}
