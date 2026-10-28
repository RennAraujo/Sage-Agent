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

    @Test
    void copyThatStartsInsideASentenceAndDropsItsQualifierIsNotACopy() {
        String premise =
                "Records" + NL + "Antes do Java 24, records eram um recurso em preview. Records sao classes finais.";

        assertThat(PremiseWindows.copiedFrom(
                        premise, "Records eram um recurso em preview; records sao classes finais."))
                .isFalse();
    }

    @Test
    void copyOfAListItemUnderAVersionLineIsNotACopy() {
        String premise = "Até o Java 23:" + NL + "- O recurso X não é suportado em modo nativo.";

        assertThat(PremiseWindows.copiedFrom(premise, "O recurso X não é suportado em modo nativo."))
                .isFalse();
    }

    @Test
    void laterListItemUnderAVersionLineIsNotACopyEither() {
        String premise = "Notas" + NL + "Desde o Java 21 vale o seguinte" + NL + "- Primeiro item da lista de regras."
                + NL + "- O recurso X não é suportado em modo nativo.";

        assertThat(PremiseWindows.copiedFrom(premise, "O recurso X não é suportado em modo nativo."))
                .isFalse();
    }

    @Test
    void ninetyPercentOfASentenceThatDropsAQualifierIsNotACopy() {
        String premise = "Records. A partir do Java 16 o recurso records e estavel e pode ser usado em producao.";

        assertThat(PremiseWindows.copiedFrom(premise, "o recurso records e estavel e pode ser usado em producao."))
                .isFalse();
    }

    @Test
    void copyUnderASubjectLineThatNamesAVersionIsNotACopy() {
        String probe = "Java 17 e anteriores" + NL + "O coletor padrao e o Parallel GC nessa linha.";
        String breadcrumb = "GC > Java 17 e anteriores" + NL + "O coletor padrao e o Parallel GC nessa linha.";

        assertThat(PremiseWindows.copiedFrom(probe, "O coletor padrao e o Parallel GC nessa linha."))
                .isFalse();
        assertThat(PremiseWindows.copiedFrom(breadcrumb, "O coletor padrao e o Parallel GC nessa linha."))
                .isFalse();
    }

    @Test
    void wholeSentenceAfterAPlainSentenceIsStillACopy() {
        String premise =
                "Records" + NL + "Antes do Java 24, records eram um recurso em preview. Records sao classes finais.";

        assertThat(PremiseWindows.copiedFrom(premise, "Records sao classes finais."))
                .isTrue();
        assertThat(PremiseWindows.copiedFrom(premise, "Antes do Java 24, records eram um recurso em preview."))
                .isTrue();
    }

    @Test
    void listItemUnderAPlainLineIsStillACopy() {
        String premise = "Coleções" + NL + "Os métodos principais são estes." + NL
                + "- O método toList devolve uma lista imutável.";

        assertThat(PremiseWindows.copiedFrom(premise, "O método toList devolve uma lista imutável."))
                .isTrue();
    }
}
