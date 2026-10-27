package io.github.rennaraujo.sage.question;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import io.github.rennaraujo.sage.document.DocumentDeletedEvent;
import io.github.rennaraujo.sage.document.DocumentSavedEvent;
import io.github.rennaraujo.sage.document.Source;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class QuestionAnalyzerTest {

    private final AliasCatalog catalog = new AliasCatalog(new FakeCatalogs.Store(
            FakeCatalogs.internal("portal-x", List.of("faturamento-x")), FakeCatalogs.publicDoc("java", "25")));
    private final QuestionAnalyzer analyzer = new QuestionAnalyzer(catalog);

    private QuestionAnalysis analyze(String question) {
        return analyzer.analyze(question, Optional.empty());
    }

    @Test
    void shortInternalAliasInsideCamelCaseOrDigitTokensRoutesAsInternal() {
        QuestionAnalyzer erp = new QuestionAnalyzer(new AliasCatalog(new FakeCatalogs.Store(
                FakeCatalogs.internal("erp", List.of("sga")), FakeCatalogs.publicDoc("spring-boot", "3.5"))));

        for (String question : List.of(
                "como configurar o ErpClient no Spring Boot 3.5",
                "o erp2 quebrou no Spring Boot 3.5",
                "ERPService no Spring Boot 3.5",
                "falha no sga2 com Spring Boot 3.5")) {
            QuestionAnalysis a = erp.analyze(question, Optional.empty());
            assertThat(a.sources()).as(question).contains(Source.INTERNAL);
            assertThat(a.mixed()).as(question).isTrue();
        }
        assertThat(erp.analyze("como funciona o interpreter no Spring Boot 3.5", Optional.empty())
                        .sources())
                .containsExactly(Source.PUBLIC);
    }

    @Test
    void javaQuestionIsPublicLookup() {
        QuestionAnalysis a = analyze("No Java 25 como usar Stream.toList?");
        assertThat(a.sources()).containsExactly(Source.PUBLIC);
        assertThat(a.language()).contains("java");
        assertThat(a.versions()).containsExactly("25");
        assertThat(a.type()).isEqualTo(QuestionType.LOOKUP);
        assertThat(a.mixed()).isFalse();
        assertThat(a.publicLanguageMatched()).isTrue();
    }

    @Test
    void mixedComparisonWithArticleAfterConnector() {
        QuestionAnalysis a = analyze("isso mudou do java 17 para o 21 no portal x?");
        assertThat(a.sources()).containsExactlyInAnyOrder(Source.PUBLIC, Source.INTERNAL);
        assertThat(a.versions()).containsExactly("17", "21");
        assertThat(a.type()).isEqualTo(QuestionType.COMPARISON);
        assertThat(a.mixed()).isTrue();
    }

    @Test
    void jdkGluedToNumber() {
        QuestionAnalysis a = analyze("o que mudou no jdk21");
        assertThat(a.language()).contains("java");
        assertThat(a.versions()).containsExactly("21");
    }

    @Test
    void springBootComparison() {
        QuestionAnalysis a = analyze("diferença entre Spring Boot 3.5 e 4.1");
        assertThat(a.language()).contains("spring-boot");
        assertThat(a.versions()).containsExactly("3.5", "4.1");
        assertThat(a.type()).isEqualTo(QuestionType.COMPARISON);
    }

    @Test
    void springBootSingleVersionIsLookup() {
        QuestionAnalysis a = analyze("o que é novo no spring boot 4.1");
        assertThat(a.versions()).containsExactly("4.1");
        assertThat(a.type()).isEqualTo(QuestionType.LOOKUP);
    }

    @Test
    void internalAliasIsFlow() {
        QuestionAnalysis a = analyze("como funciona o fluxo de faturamento no Portal X");
        assertThat(a.sources()).containsExactly(Source.INTERNAL);
        assertThat(a.language()).isEmpty();
        assertThat(a.versions()).isEmpty();
        assertThat(a.type()).isEqualTo(QuestionType.FLOW);
        assertThat(a.mixed()).isFalse();
        assertThat(a.publicLanguageMatched()).isFalse();
    }

    @Test
    void hyphenatedAppNameAndFrontmatterAliasMatch() {
        assertThat(analyze("o que é o portal-x?").sources()).containsExactly(Source.INTERNAL);
        assertThat(analyze("regras do Faturamento-X").sources()).containsExactly(Source.INTERNAL);
    }

    @Test
    void mixedQuestionKeepsPublicSide() {
        QuestionAnalysis a = analyze("@Transactional no faturamento do Portal X com Spring Boot 4.1");
        assertThat(a.mixed()).isTrue();
        assertThat(a.sources()).containsExactlyInAnyOrder(Source.PUBLIC, Source.INTERNAL);
        assertThat(a.language()).contains("spring-boot");
        assertThat(a.versions()).containsExactly("4.1");
        assertThat(a.publicLanguageMatched()).isTrue();
    }

    @Test
    void unknownQuestionSearchesBothSources() {
        QuestionAnalysis a = analyze("como configurar logs");
        assertThat(a.sources()).isEqualTo(Set.of(Source.PUBLIC, Source.INTERNAL));
        assertThat(a.language()).isEmpty();
        assertThat(a.publicLanguageMatched()).isFalse();
        assertThat(a.type()).isEqualTo(QuestionType.LOOKUP);
    }

    @Test
    void followUpWithVersionInheritsLanguage() {
        QuestionAnalysis prev = analyze("No Java 25 como usar Stream.toList?");
        QuestionAnalysis a = analyzer.analyze("e na versão 21?", Optional.of(prev));
        assertThat(a.language()).contains("java");
        assertThat(a.versions()).containsExactly("21");
        assertThat(a.sources()).containsExactly(Source.PUBLIC);
        assertThat(a.publicLanguageMatched()).isTrue();
    }

    @Test
    void followUpWithoutAnythingInheritsLanguageAndVersion() {
        QuestionAnalysis prev = analyze("No Java 25 como usar Stream.toList?");
        QuestionAnalysis a = analyzer.analyze("e o toList?", Optional.of(prev));
        assertThat(a.language()).contains("java");
        assertThat(a.versions()).containsExactly("25");
        assertThat(a.sources()).containsExactly(Source.PUBLIC);
    }

    @Test
    void newTopicDoesNotInherit() {
        QuestionAnalysis prev = analyze("No Java 25 como usar Stream.toList?");
        QuestionAnalysis a = analyzer.analyze("como funciona o fluxo de faturamento no Portal X", Optional.of(prev));
        assertThat(a.sources()).containsExactly(Source.INTERNAL);
        assertThat(a.language()).isEmpty();
        assertThat(a.versions()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "qual o fluxo de login|FLOW",
                "passo a passo para configurar|FLOW",
                "explique o processo de deploy|FLOW",
                "diferença entre as duas abordagens|COMPARISON",
                "como fazer a migração para a nova API|COMPARISON",
                "comparar HashMap e TreeMap|COMPARISON",
                "record versus class|COMPARISON",
                "list vs. set|COMPARISON",
                "o que é um record|LOOKUP"
            })
    void typeRules(String question, QuestionType expected) {
        assertThat(analyze(question).type()).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {"uso de javascript no front|false", "java é legal|true", "JAVA 25|true", "o jav é nada|false"})
    void aliasWordBoundary(String question, boolean javaMatched) {
        assertThat(analyze(question).language().isPresent()).isEqualTo(javaMatched);
    }

    @Test
    void versionsAreDedupedInOrder() {
        QuestionAnalysis a = analyze("java 17, java 21 e de novo java 17");
        assertThat(a.versions()).containsExactly("17", "21");
        assertThat(a.type()).isEqualTo(QuestionType.COMPARISON);
    }

    @Test
    void chainedVersionsAreCollected() {
        assertThat(analyze("java 17 vs 21?").versions()).containsExactly("17", "21");
        assertThat(analyze("java 17 e 3 exemplos").versions()).containsExactly("17");
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "diferença entre Java 17 e 21 em performance|17,21",
                "Java 8 vs 17 para records|8,17",
                "Java 17 vs 21 no deploy|17,21",
                "java 8 para 17|8,17",
                "java 17 x 21|17,21",
                "java 17 versus 21|17,21",
                "java 17 -> 21|17,21",
                "java 1.8 vs 17|8,17"
            })
    void integerAfterComparisonConnectorIsAVersion(String question, String expected) {
        assertThat(analyze(question).versions()).containsExactly(expected.split(","));
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {"java 2024", "java 17 e 2024", "java 17 e 3 exemplos", "java 17 e 5 vezes", "java 17 em 10 minutos"
            })
    void countsAndYearsAreNotVersions(String question) {
        QuestionAnalysis a = analyze(question);
        assertThat(a.language()).contains("java");
        assertThat(a.versions()).doesNotContain("3", "2024", "5", "10", "20");
        if (question.contains("17")) {
            assertThat(a.versions()).containsExactly("17");
        } else {
            assertThat(a.versions()).isEmpty();
        }
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "COMO FAZER A MIGRAÇÃO PARA O JAVA|COMPARISON",
                "DIFERENÇA ENTRE DOIS|COMPARISON",
                "QUAL É O FLUXO DE LOGIN|FLOW",
                "Qual é o fluxo|FLOW",
                "PASSO A PASSO|FLOW"
            })
    void typeRulesIgnoreCaseAndAccents(String question, QuestionType expected) {
        assertThat(analyze(question).type()).isEqualTo(expected);
    }

    @Test
    void uppercaseAccentedVersionKeyword() {
        QuestionAnalysis prev = analyze("No Java 25 como usar Stream.toList?");
        QuestionAnalysis a = analyzer.analyze("E NA VERSÃO 21?", Optional.of(prev));
        assertThat(a.language()).contains("java");
        assertThat(a.versions()).containsExactly("21");
    }

    @Test
    void decomposedUnicodeInputMatches() {
        String nfd = java.text.Normalizer.normalize("como fazer a migração", java.text.Normalizer.Form.NFD);
        assertThat(analyze(nfd).type()).isEqualTo(QuestionType.COMPARISON);
        QuestionAnalysis prev = analyze("No Java 25 como usar Stream.toList?");
        String nfdVersion = java.text.Normalizer.normalize("e na versão 21?", java.text.Normalizer.Form.NFD);
        assertThat(analyzer.analyze(nfdVersion, Optional.of(prev)).versions()).containsExactly("21");
    }

    @Test
    void shortJOnlyWhenGluedToDigits() {
        assertThat(analyze("opção j 2").language()).isEmpty();
        assertThat(analyze("tipo j 5 do formulário").language()).isEmpty();
        assertThat(analyze("usando j21").versions()).containsExactly("21");
        assertThat(analyze("usando j-17").versions()).containsExactly("17");
        assertThat(analyze("java 1.8").versions()).containsExactly("8");
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "Java 21 threads virtuais|java|21",
                "Java 17 classes seladas|java|17",
                "Spring Boot 3.5 testes|spring-boot|3.5",
                "java 17 e 3 passos|java|17"
            })
    void primaryVersionIsNeverDroppedByCountedNoun(String question, String language, String version) {
        QuestionAnalysis a = analyze(question);
        assertThat(a.language()).contains(language);
        assertThat(a.versions()).containsExactly(version);
    }

    @Test
    void existenceQuestionIsComparisonWithBothVersions() {
        QuestionAnalysis a = analyze("isso existe no java 25 ou é exclusivo do java 8?");
        assertThat(a.language()).contains("java");
        assertThat(a.versions()).containsExactly("25", "8");
        assertThat(a.type()).isEqualTo(QuestionType.COMPARISON);
    }

    @Test
    void introducedInWhichVersionIsComparisonWithoutVersions() {
        QuestionAnalysis a = analyze("em qual versão do java foi introduzido o record?");
        assertThat(a.language()).contains("java");
        assertThat(a.versions()).isEmpty();
        assertThat(a.type()).isEqualTo(QuestionType.COMPARISON);
    }

    @Test
    void singleExplicitVersionStaysLookup() {
        QuestionAnalysis a = analyze("traga a documentação do java 21 sobre virtual threads");
        assertThat(a.language()).contains("java");
        assertThat(a.versions()).containsExactly("21");
        assertThat(a.type()).isEqualTo(QuestionType.LOOKUP);
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "isso existe na api de streams|COMPARISON",
                "é exclusiva do spring boot 4|COMPARISON",
                "está disponível a partir do java 17?|COMPARISON",
                "a partir de qual versão isso vale|COMPARISON",
                "o método foi removido?|COMPARISON",
                "o método foi introduzida|COMPARISON",
                "desde o java 11|COMPARISON",
                "desde qual release|COMPARISON",
                "qual versão uso|COMPARISON"
            })
    void existenceRulesAreComparison(String question, QuestionType expected) {
        assertThat(analyze(question).type()).isEqualTo(expected);
    }

    @Test
    void nullQuestionIsRejected() {
        assertThatThrownBy(() -> analyzer.analyze(null, Optional.empty())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void catalogReloadsOnDocumentEvents() {
        FakeCatalogs.Store store = new FakeCatalogs.Store();
        AliasCatalog dynamic = new AliasCatalog(store);
        QuestionAnalyzer dyn = new QuestionAnalyzer(dynamic);
        assertThat(dyn.analyze("regras do app legado", Optional.empty()).sources())
                .containsExactlyInAnyOrder(Source.PUBLIC, Source.INTERNAL);
        store.items.add(FakeCatalogs.internal("legado", List.of()));
        dynamic.onSaved(new DocumentSavedEvent(null));
        assertThat(dyn.analyze("regras do app legado", Optional.empty()).sources())
                .containsExactly(Source.INTERNAL);
        store.items.clear();
        dynamic.onDeleted(new DocumentDeletedEvent(null));
        assertThat(dyn.analyze("regras do app legado", Optional.empty()).sources())
                .containsExactlyInAnyOrder(Source.PUBLIC, Source.INTERNAL);
    }

    @Test
    void adversarialInputCompletesFast() {
        List<String> inputs = List.of(
                "java ".repeat(400),
                "j".repeat(2000),
                "spring ".repeat(285),
                "spring boot ".repeat(166),
                " ".repeat(2000),
                "versão ".repeat(285),
                "0.".repeat(1000),
                "versão " + "1.".repeat(990));
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> inputs.forEach(this::analyze));
    }
}
