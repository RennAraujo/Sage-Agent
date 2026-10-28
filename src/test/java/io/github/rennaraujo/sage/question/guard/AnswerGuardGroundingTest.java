package io.github.rennaraujo.sage.question.guard;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rennaraujo.sage.question.llm.PromptBuilder;
import io.github.rennaraujo.sage.upload.SecretScanner;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Fundamentação (NLI) e filtro de segredos dentro do guard. As regras de citação ficam em {@link AnswerGuardTest}. */
class AnswerGuardGroundingTest {

    private static final List<String> EVIDENCE = List.of("trecho um", "trecho dois", "trecho tres", "trecho quatro");
    private static final String REDACTED = "[conteúdo removido: possível segredo]";
    private static final String AWS_KEY = "AKIAABCDEFGHIJKLMNOP";

    private static AnswerGuard guard(GroundednessChecker checker) {
        return new AnswerGuard(EVIDENCE, false, false, checker, new SecretScanner());
    }

    /** Guard de comparação em que todos os trechos são da mesma linguagem e versão. */
    private static AnswerGuard comparison(GroundednessChecker checker, String source) {
        return new AnswerGuard(
                EVIDENCE,
                java.util.Collections.nCopies(EVIDENCE.size(), source),
                true,
                false,
                checker,
                new SecretScanner());
    }

    @Test
    void absenceTemplateNamingAnotherVersionIsDroppedWithoutReachingTheChecker() {
        FakeGroundednessChecker checker = FakeGroundednessChecker.alwaysSupported();
        AnswerGuard guard = comparison(checker, "Java 8");

        List<String> out = run(guard, "A documentação do Java 25 disponível não menciona records, mas isso muda [1].");

        assertThat(out).containsExactly(PromptBuilder.NOT_COVERED);
        assertThat(checker.calls()).isEmpty();
    }

    @Test
    void unsupportedSentenceDropped() {
        AnswerGuard guard = guard(FakeGroundednessChecker.supportingSentencesWith("imutável"));

        List<String> out = run(guard, "A lista é imutável [1]. A lista é mutável [2].");

        assertThat(out).containsExactly("A lista é imutável [1].");
        assertThat(guard.dropped()).isEqualTo(1);
    }

    @Test
    void supportedSentencePassesAndIsCited() {
        AnswerGuard guard = guard(FakeGroundednessChecker.alwaysSupported());

        assertThat(run(guard, "Use toList() [3].")).containsExactly("Use toList() [3].");
        assertThat(guard.cited()).containsExactly(3);
        assertThat(guard.dropped()).isZero();
    }

    @Test
    void unsupportedSentenceDoesNotCountAsCitedNorAsAccepted() {
        AnswerGuard guard = guard(FakeGroundednessChecker.neverSupported());

        assertThat(run(guard, "Inventado [2].")).containsExactly(PromptBuilder.NOT_COVERED);
        assertThat(guard.cited()).isEmpty();
    }

    @Test
    void checkerSeesOnlyTheCitedChunksAndTheSentenceWithoutMarkers() {
        FakeGroundednessChecker checker = FakeGroundednessChecker.alwaysSupported();
        AnswerGuard guard = guard(checker);

        run(guard, "Combine `args[0]` com o resto [2] [4].");

        assertThat(checker.calls()).hasSize(1);
        assertThat(checker.calls().get(0).sentence()).isEqualTo("Combine `args[0]` com o resto.");
        assertThat(checker.calls().get(0).evidence()).containsExactly("trecho dois", "trecho quatro");
    }

    @Test
    void repeatedCitationSendsTheChunkOnce() {
        FakeGroundednessChecker checker = FakeGroundednessChecker.alwaysSupported();

        run(guard(checker), "Veja isto [2] e aquilo [2].");

        assertThat(checker.calls().get(0).evidence()).containsExactly("trecho dois");
    }

    @Test
    void sentenceFailingTheCitationCheckIsNotSentToTheChecker() {
        FakeGroundednessChecker checker = FakeGroundednessChecker.alwaysSupported();

        run(guard(checker), "Sem citação. Citação fora [9].");

        assertThat(checker.calls()).isEmpty();
    }

    @Test
    void numberedFlowStepsAreCheckedLikeAnySentence() {
        FakeGroundednessChecker checker = FakeGroundednessChecker.supportingSentencesWith("valida");
        AnswerGuard guard = new AnswerGuard(EVIDENCE, false, true, checker, new SecretScanner());

        List<String> out = run(guard, "1. O serviço valida o token [1].\n2. O serviço apaga tudo [2].\n");

        assertThat(checker.calls()).hasSize(2);
        assertThat(out).hasSize(1);
        assertThat(out.get(0)).contains("valida");
        assertThat(guard.dropped()).isEqualTo(1);
    }

    @Test
    void codeBlockIsNotCheckedByTheChecker() {
        FakeGroundednessChecker checker = FakeGroundednessChecker.alwaysSupported();

        List<String> out = run(guard(checker), "Use toList() [1].\n```java\nvar l = s.toList();\n```\n");

        assertThat(out).hasSize(2);
        assertThat(checker.calls()).hasSize(1);
    }

    @Test
    void fixedTextsAreNotChecked() {
        FakeGroundednessChecker checker = FakeGroundednessChecker.neverSupported();
        AnswerGuard flow = new AnswerGuard(EVIDENCE, false, true, checker, new SecretScanner());

        run(flow, PromptBuilder.FLOW_NOT_DETAILED);
        List<String> notCovered = run(guard(checker), PromptBuilder.NOT_COVERED);

        assertThat(checker.calls()).isEmpty();
        assertThat(flow.flowNotDetailed()).isTrue();
        assertThat(notCovered).containsExactly(PromptBuilder.NOT_COVERED);
    }

    @Test
    void absenceTemplateSentenceInComparisonIsNotChecked() {
        FakeGroundednessChecker checker = FakeGroundednessChecker.neverSupported();
        AnswerGuard guard = comparison(checker, "Java 8");

        List<String> out = run(guard, "A documentação do Java 8 disponível não menciona records [1].");

        assertThat(out).containsExactly("A documentação do Java 8 disponível não menciona records [1].");
        assertThat(checker.calls()).isEmpty();
    }

    @Test
    void absenceTemplateWithASecondClauseStillGoesThroughTheChecker() {
        FakeGroundednessChecker checker = FakeGroundednessChecker.neverSupported();
        AnswerGuard guard = comparison(checker, "Java 21");

        List<String> out =
                run(guard, "A documentação do Java 21 disponível não menciona records, mas records são mutáveis [1].");

        assertThat(checker.calls()).hasSize(1);
        assertThat(out).containsExactly(PromptBuilder.NOT_COVERED);
        assertThat(guard.dropped()).isEqualTo(1);
    }

    @Test
    void absenceTemplateWithSemicolonOrLongSubjectIsChecked() {
        FakeGroundednessChecker checker = FakeGroundednessChecker.neverSupported();

        run(
                comparison(checker, "Java 8"),
                "A documentação do Java 8 disponível não menciona records; eles são mutáveis [1].");
        run(
                comparison(checker, "Java 8"),
                "A documentação do Java 8 disponível não menciona " + "uma coisa muito comprida ".repeat(5) + "[1].");

        assertThat(checker.calls()).hasSize(2);
    }

    @Test
    void absenceTemplateWithAShortCommaSubjectStillSkipsTheChecker() {
        FakeGroundednessChecker checker = FakeGroundednessChecker.neverSupported();
        AnswerGuard guard = comparison(checker, "Java 8");

        List<String> out = run(guard, "A documentação do Java 8 disponível não menciona records, enums [1].");

        assertThat(checker.calls()).isEmpty();
        assertThat(out).hasSize(1);
    }

    @Test
    void supportedSentenceWithSecretIsRedacted() {
        FakeGroundednessChecker checker = FakeGroundednessChecker.alwaysSupported();
        AnswerGuard guard = guard(checker);

        List<String> out = run(guard, "A chave é " + AWS_KEY + " [1]. Outra frase normal [2].");

        assertThat(out).containsExactly(REDACTED, "Outra frase normal [2].");
        assertThat(String.join("", out)).doesNotContain(AWS_KEY);
        assertThat(guard.redacted()).isEqualTo(1);
        assertThat(guard.dropped()).isZero();
        assertThat(guard.cited()).containsExactly(2);
        assertThat(checker.calls()).hasSize(2);
    }

    @Test
    void unsupportedSentenceWithSecretIsDroppedNotRedacted() {
        AnswerGuard guard = guard(FakeGroundednessChecker.neverSupported());

        List<String> out = run(guard, "A chave é " + AWS_KEY + " [1].");

        assertThat(out).containsExactly(PromptBuilder.NOT_COVERED);
        assertThat(guard.redacted()).isZero();
        assertThat(guard.dropped()).isEqualTo(1);
    }

    @Test
    void secretSentenceWithoutCitationIsDroppedNotRedacted() {
        AnswerGuard guard = guard(FakeGroundednessChecker.alwaysSupported());

        run(guard, "A chave é " + AWS_KEY + ".");

        assertThat(guard.redacted()).isZero();
        assertThat(guard.dropped()).isEqualTo(1);
    }

    @Test
    void codeBlockWithSecretIsRedacted() {
        AnswerGuard guard = guard(FakeGroundednessChecker.alwaysSupported());

        List<String> out = run(guard, "Configure assim [1].\n```properties\naws.key=" + AWS_KEY + "\n```\n");

        assertThat(out).containsExactly("Configure assim [1].", REDACTED);
        assertThat(guard.redacted()).isEqualTo(1);
    }

    @Test
    void codeBlockAfterRedactedSentenceNeedsANewJustification() {
        AnswerGuard guard = guard(FakeGroundednessChecker.alwaysSupported());

        List<String> out = run(guard, "senha: " + AWS_KEY + " [1].\n```java\nok();\n```\n");

        assertThat(out).containsExactly(REDACTED);
        assertThat(guard.dropped()).isEqualTo(1);
    }

    @Test
    void redactedOnlyAnswerDoesNotFallBackToNotCovered() {
        AnswerGuard guard = guard(FakeGroundednessChecker.alwaysSupported());

        assertThat(run(guard, "A chave é " + AWS_KEY + " [1].")).containsExactly(REDACTED);
    }

    private static List<String> run(AnswerGuard guard, String text) {
        List<String> out = new ArrayList<>(guard.accept(text));
        out.addAll(guard.finish());
        return out;
    }
}
