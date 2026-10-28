package io.github.rennaraujo.sage.question.guard;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class PremiseWindowsTest {

    @Test
    void oneSentenceStaysWhole() {
        assertThat(PremiseWindows.of("Stream.toList() returns an unmodifiable List.", "qualquer"))
                .containsExactly("Stream.toList() returns an unmodifiable List.");
    }

    @Test
    void headingIsKeptInFrontOfEveryWindow() {
        List<String> windows =
                PremiseWindows.of("Streams > Gatherers\nPrimeira frase. Segunda frase. Terceira frase.", "x");

        assertThat(windows).hasSize(2).allSatisfy(w -> assertThat(w).startsWith("Streams > Gatherers\n"));
        assertThat(windows.get(1)).endsWith("Segunda frase. Terceira frase.");
    }

    @Test
    void sentenceLikeFirstLineIsNotHeading() {
        List<String> windows = PremiseWindows.of("Uma frase inteira com ponto.\nOutra frase.", "x");

        assertThat(windows).containsExactly("Uma frase inteira com ponto.\nOutra frase.");
    }

    @Test
    void longPremiseKeepsTheWindowsThatShareWordsWithTheSentenceAndStaysBounded() {
        StringBuilder premise = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            premise.append("Frase de enchimento número ").append(i).append(" sem relação. ");
        }
        premise.append("O timeout do gateway de pagamento é de oito segundos.");

        List<String> windows = PremiseWindows.of(premise.toString(), "O timeout do gateway é de oito segundos");

        assertThat(windows).hasSize(PremiseWindows.MAX_PER_PREMISE);
        assertThat(windows).anySatisfy(w -> assertThat(w).contains("timeout do gateway"));
    }

    private static final String NL = String.valueOf((char) 10);

    private static final String GATHERERS = "Streams > Gatherers" + NL
            + "Stream gatherers, final in Java 25, let a pipeline define its own intermediate operations."
            + " Another sentence follows here.";
    private static final String NEGATED = "Notes" + NL
            + "Not supported in Java 21 when running on the module path with preview features enabled."
            + " Use the launcher flag instead.";

    @Test
    void copyOfACompleteSentenceIgnoresCasePunctuationAndSpacing() {
        assertThat(PremiseWindows.copiedFrom(
                        GATHERERS,
                        "stream gatherers final in JAVA 25 let a pipeline define its own intermediate operations"))
                .isTrue();
        assertThat(PremiseWindows.copiedFrom(GATHERERS, "Stream gatherers are mutable in Java 25 and always preview"))
                .isFalse();
    }

    @Test
    void copyOfSeveralSentencesCounts() {
        assertThat(PremiseWindows.copiedFrom(
                        GATHERERS,
                        "Stream gatherers, final in Java 25, let a pipeline define its own intermediate operations."
                                + " Another sentence follows here."))
                .isTrue();
    }

    @Test
    void fragmentThatDropsTheStartOfASentenceIsNotACopy() {
        assertThat(PremiseWindows.copiedFrom(GATHERERS, "let a pipeline define its own intermediate operations"))
                .isFalse();
    }

    @Test
    void fragmentThatDropsANegationIsNotACopyEvenAtNinetyPercent() {
        assertThat(PremiseWindows.copiedFrom(
                        NEGATED, "supported in Java 21 when running on the module path with preview features enabled"))
                .isFalse();
        assertThat(PremiseWindows.copiedFrom(
                        NEGATED,
                        "Not supported in Java 21 when running on the module path with preview features enabled"))
                .isTrue();
    }

    @Test
    void fragmentThatDropsAQualifierIsNotACopy() {
        String premise = "Stream.toList() returns an unmodifiable List only when the stream has no null elements."
                + " The list is built eagerly.";

        assertThat(PremiseWindows.copiedFrom(premise, "Stream.toList() returns an unmodifiable List"))
                .isFalse();
    }

    @Test
    void bestKeepsTheWindowsClosestToTheSentenceUpToTheCap() {
        List<String> windows = List.of(
                "ruído um sem relação",
                "o timeout do gateway é oito",
                "ruído dois",
                "ruído três",
                "gateway sem timeout");

        assertThat(PremiseWindows.best(windows, "timeout do gateway", 2))
                .containsExactly("o timeout do gateway é oito", "gateway sem timeout");
        assertThat(PremiseWindows.best(windows, "timeout do gateway", 10)).isEqualTo(windows);
    }

    @Test
    void shortSentencesNeverCountAsCopies() {
        assertThat(PremiseWindows.copiedFrom("Use a lista para guardar os itens.", "a lista"))
                .isFalse();
    }
}
