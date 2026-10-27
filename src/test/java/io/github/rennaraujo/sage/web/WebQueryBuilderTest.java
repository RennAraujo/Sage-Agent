package io.github.rennaraujo.sage.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rennaraujo.sage.document.Document;
import io.github.rennaraujo.sage.document.DocumentId;
import io.github.rennaraujo.sage.document.DocumentMetadata;
import io.github.rennaraujo.sage.document.DocumentStore;
import io.github.rennaraujo.sage.document.Source;
import io.github.rennaraujo.sage.question.AliasCatalog;
import io.github.rennaraujo.sage.question.QuestionAnalysis;
import io.github.rennaraujo.sage.question.QuestionType;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class WebQueryBuilderTest {

    private static DocumentMetadata internal(String app, List<String> aliases) {
        return new DocumentMetadata(
                DocumentId.of("interno/" + app + "/guia.md"),
                Source.INTERNAL,
                app,
                Optional.empty(),
                "guia",
                aliases,
                Optional.empty(),
                LocalDate.of(2026, 10, 1),
                true);
    }

    private static final class FixedStore implements DocumentStore {
        private final List<DocumentMetadata> items;

        FixedStore(DocumentMetadata... items) {
            this.items = List.of(items);
        }

        @Override
        public void save(Document doc) {}

        @Override
        public boolean saveIfAbsent(Document doc) {
            return false;
        }

        @Override
        public Optional<Document> find(DocumentId id) {
            return Optional.empty();
        }

        @Override
        public List<DocumentMetadata> listMetadata() {
            return items;
        }

        @Override
        public void delete(DocumentId id) {}

        @Override
        public Stream<Document> loadAll() {
            return Stream.empty();
        }
    }

    private final WebQueryBuilder builder = new WebQueryBuilder(
            new AliasCatalog(new FixedStore(internal("faturamento-api", List.of("fatura", "billing core")))));

    private String queryOf(String question, QuestionAnalysis analysis) {
        return builder.build(question, analysis).orElseThrow();
    }

    private static QuestionAnalysis analysis(String language, String... versions) {
        return new QuestionAnalysis(
                Set.of(Source.PUBLIC), Optional.of(language), List.of(versions), QuestionType.LOOKUP, false, true);
    }

    @Test
    void usesLanguageNameVersionAndMeaningfulTokens() {
        String q = queryOf("Como funciona o CompletableFuture no Java 25?", analysis("java", "25"));

        assertThat(q).startsWith("Java 25").contains("CompletableFuture").contains("funciona");
        assertThat(q).doesNotContain(" o ").doesNotContain(" no ").doesNotContain("?");
    }

    @Test
    void spellsFrameworkIdsAsNames() {
        assertThat(queryOf("quais são as propriedades de actuator?", analysis("spring-boot", "3.5")))
                .startsWith("Spring Boot 3.5")
                .contains("actuator")
                .contains("propriedades");
    }

    @Test
    void queryContainsNoInternalAlias() {
        String q = queryOf(
                "Como o billing core e a fatura usam o faturamento-api com virtual threads no java 21?",
                analysis("java", "21"));

        assertThat(q.toLowerCase())
                .doesNotContain("billing")
                .doesNotContain("fatura")
                .doesNotContain("faturamento");
        assertThat(q).contains("virtual").contains("threads");
    }

    @Test
    void dropsAliasesRegardlessOfCaseAndAccents() {
        QuestionAnalysis a = analysis("java", "21");

        assertThat(queryOf("FATURA e Billing   CORE em records", a).toLowerCase())
                .doesNotContain("fatura")
                .doesNotContain("billing");
    }

    @Test
    void capsTheQueryLength() {
        String longQuestion = "palavra ".repeat(5) + "token".repeat(10) + " " + "outro ".repeat(200);

        String q = queryOf(longQuestion, analysis("java", "21"));

        assertThat(q.length()).isLessThanOrEqualTo(WebQueryBuilder.MAX_QUERY_CHARS);
        assertThat(q).doesNotEndWith(" ");
    }

    @Test
    void worksWithoutVersionAndRemovesDuplicates() {
        String q = queryOf("java streams streams java", analysis("java"));

        assertThat(q).isEqualTo("Java streams");
    }

    @Test
    void compoundTokensDoNotLeakAliases() {
        QuestionAnalysis a = analysis("java", "21");

        for (String question : new String[] {
            "erro no billing_core_api no Java 21",
            "erro em api.billing.core no Java 21",
            "erro no BillingCoreApi no Java 21",
            "erro no faturamento_api_v2 no Java 21",
            "erro no x-billing-core-y no Java 21"
        }) {
            String query = queryOf(question, a);
            assertThat(query.toLowerCase())
                    .as(question)
                    .doesNotContain("billing")
                    .doesNotContain("faturamento");
            assertThat(query).as(question).contains("erro");
        }
    }

    @Test
    void abortsWhenTheAssembledQueryStillCarriesAnAlias() {
        WebQueryBuilder longAlias = new WebQueryBuilder(new AliasCatalog(
                new FixedStore(internal("x", List.of("alfa bravo charlie delta echo foxtrot golf hotel")))));

        assertThat(longAlias.build(
                        "como alfa bravo charlie delta echo foxtrot golf hotel funciona", analysis("java", "21")))
                .isEmpty();
    }

    @Test
    void shortAliasesDoNotLeakInsideCamelCaseOrDigitTokens() {
        WebQueryBuilder erp = new WebQueryBuilder(new AliasCatalog(new FixedStore(internal("erp", List.of()))));
        QuestionAnalysis a = analysis("spring-boot", "3.5");

        for (String question : List.of(
                "como configurar o ErpClient no Spring Boot 3.5",
                "o erp2 quebrou",
                "ERPService falhou",
                "o erp_v2 falhou")) {
            Optional<String> query = erp.build(question, a);
            assertThat(query.orElse("")).as(question).doesNotContainIgnoringCase("erp");
        }
        assertThat(erp.build("como funciona o interpreter", a).orElseThrow()).contains("interpreter");
    }
}
