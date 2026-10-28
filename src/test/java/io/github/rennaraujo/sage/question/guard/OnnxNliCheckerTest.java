package io.github.rennaraujo.sage.question.guard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rennaraujo.sage.testsupport.LatencyBudget;
import io.github.rennaraujo.sage.testsupport.ScratchDirs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Roda só se os arquivos do modelo de NLI existirem em {@code sage.models.dir} (padrão {@code models}). */
@Tag("models")
@EnabledIf("modelsPresent")
class OnnxNliCheckerTest {

    private static final Logger LOG = LoggerFactory.getLogger(OnnxNliCheckerTest.class);
    private static final GuardProperties PROPERTIES = new GuardProperties(0.5, 256, false);
    private static final String EVIDENCE = "Stream.toList() returns an unmodifiable List.";
    private static final int HUGE_WORDS = 5000;
    private static final int TIMED_RUNS = 10;
    private static final int TYPICAL_EVIDENCE_REPEATS = 8;
    /** Teto generoso: o valor medido está em docs/seguranca.md; isto só pega regressão grande. */
    private static final long MAX_MILLIS_PER_SENTENCE = 1500;

    private static final long WORST_CASE_MAX_MILLIS = 4000;
    private static final int CONTEXT_CHUNKS = 5;
    private static final double MIN_ACCURACY_PERCENT = 85.0;
    private static final int CEILING_REPEATS = 28;

    private static OnnxNliChecker checker;

    static boolean modelsPresent() {
        return filesFromProperty().available();
    }

    private static NliFiles filesFromProperty() {
        return NliFiles.inDir(Path.of(System.getProperty("sage.models.dir", "models")));
    }

    @BeforeAll
    static void load() {
        checker = new OnnxNliChecker(filesFromProperty(), PROPERTIES);
    }

    @AfterAll
    static void unload() {
        if (checker != null) {
            checker.close();
        }
    }

    @Test
    void entailedPairSupported() {
        assertThat(checker.isSupported("O método toList retorna uma lista imutável.", List.of(EVIDENCE)))
                .isTrue();
    }

    @Test
    void contradictionNotSupported() {
        assertThat(checker.isSupported("O método toList retorna uma lista mutável.", List.of(EVIDENCE)))
                .isFalse();
    }

    @Test
    void unrelatedSentenceNotSupported() {
        assertThat(checker.isSupported("O Spring Boot usa Tomcat como servidor padrão.", List.of(EVIDENCE)))
                .isFalse();
    }

    @Test
    void supportedWhenAnyOfTheCitedChunksEntailsIt() {
        List<String> evidence = List.of("Configure TLS com um SSL bundle no Spring Boot.", EVIDENCE);

        assertThat(checker.isSupported("O método toList retorna uma lista imutável.", evidence))
                .isTrue();
    }

    /** Chunk real do acervo de avaliação (Java 25, "Streams > Gatherers"): a frase está nele, entre outras. */
    private static final String LONG_CHUNK = "Streams > Gatherers\n"
            + "Stream gatherers, final in Java 25, let a pipeline define its own intermediate operations. "
            + "Stream.gather(Gatherer) applies a gatherer, and the java.util.stream.Gatherers class has ready-made "
            + "ones: windowFixed groups elements into lists of a fixed size, windowSliding builds overlapping windows, "
            + "fold and scan accumulate state, and mapConcurrent maps elements concurrently with a bound on the number "
            + "of tasks, using virtual threads.\n\n"
            + "A gatherer can keep private state, emit zero or more elements for each input and short-circuit the "
            + "stream, which is what makes operations such as windowing possible without collecting the whole "
            + "stream first.";

    @Test
    void sentenceCopiedFromInsideALongChunkIsSupported() {
        assertThat(checker.isSupported(
                        "Stream gatherers, final in Java 25, let a pipeline define its own intermediate operations.",
                        List.of(LONG_CHUNK)))
                .isTrue();
    }

    @Test
    void ptBrParaphraseOfOneSentenceOfALongChunkIsSupported() {
        assertThat(checker.isSupported(
                        "Os gatherers do Java 25 permitem que um pipeline defina as próprias operações intermediárias.",
                        List.of(LONG_CHUNK)))
                .isTrue();
    }

    @Test
    void fragmentThatDropsANegationIsJudgedByTheModelAndRejected() {
        String chunk = "Notes\n"
                + "Not supported in Java 21 when running on the module path with preview features enabled."
                + " Use the launcher flag instead.";

        assertThat(checker.isSupported(
                        "supported in Java 21 when running on the module path with preview features enabled",
                        List.of(chunk)))
                .isFalse();
    }

    @Test
    void windowingDoesNotLetAContradictionPassThroughALongChunk() {
        assertThat(checker.isSupported(
                        "Os gatherers do Java 25 são uma função em preview que precisa de --enable-preview.",
                        List.of(LONG_CHUNK)))
                .isFalse();
    }

    @Test
    void noEvidenceMeansNotSupported() {
        assertThat(checker.isSupported("Qualquer frase.", List.of())).isFalse();
    }

    @Test
    void sentenceOf2000WordsDoesNotThrow() {
        String sentence = "palavra ".repeat(HUGE_WORDS / 2);

        assertThat(checker.isSupported(sentence, List.of(EVIDENCE))).isFalse();
    }

    @Test
    void sentenceOfOneHugeTokenDoesNotThrow() {
        String sentence = "a".repeat(4000);

        assertThat(checker.isSupported(sentence, List.of(EVIDENCE))).isFalse();
    }

    @Test
    void veryLongEvidenceIsTruncatedInsteadOfFailing() {
        String huge = "palavra ".repeat(HUGE_WORDS);

        assertThatCode(() -> checker.isSupported("A palavra aparece no texto.", List.of(huge)))
                .doesNotThrowAnyException();
    }

    @Test
    void hugeSentenceAndHugeEvidenceTogetherDoNotThrow() {
        String huge = "palavra ".repeat(HUGE_WORDS);

        assertThatCode(() -> checker.isSupported(huge, List.of(huge, huge))).doesNotThrowAnyException();
    }

    @Test
    void zeroWidthOrNulSentenceDoesNotThrow() {
        for (String junk : List.of("\u200b\u200b\u200b", "\u0000\u0000", "\u200d\ufeff\u2060")) {
            assertThatCode(() -> checker.isSupported(junk, List.of(EVIDENCE))).doesNotThrowAnyException();
        }
    }

    @Test
    void zeroWidthOrNulEvidenceDoesNotThrow() {
        for (String junk : List.of("\u200b\u200b\u200b", "\u0000\u0000", "\u200d\ufeff\u2060")) {
            assertThatCode(() -> checker.isSupported("O método toList retorna uma lista imutável.", List.of(junk)))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void evidenceOfOneHugeTokenDoesNotThrow() {
        String huge = "a".repeat(20_000);

        assertThat(checker.isSupported("O método toList retorna uma lista imutável.", List.of(huge)))
                .isFalse();
    }

    @Test
    void agreesWithTheLabelledPairsAtLeast85Percent() throws IOException {
        int total = 0;
        int agreed = 0;
        try (var in = OnnxNliCheckerTest.class.getResourceAsStream("nli-pairs.tsv")) {
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] cols = line.split("\t");
                boolean expected = "yes".equals(cols[2].strip());
                total++;
                if (checker.isSupported(cols[1], List.of(cols[0])) == expected) {
                    agreed++;
                }
            }
        }
        double accuracy = 100.0 * agreed / total;
        LOG.info("nli: concordância {}/{} = {} % no limiar 0.5", agreed, total, Math.round(accuracy * 10) / 10.0);
        assertThat(total).isGreaterThanOrEqualTo(20);
        assertThat(accuracy).isGreaterThanOrEqualTo(MIN_ACCURACY_PERCENT);
    }

    @Test
    void perSentenceLatencyIsLogged() {
        List<String> evidence = List.of(
                ("Stream.toList() returns an unmodifiable List. ").repeat(TYPICAL_EVIDENCE_REPEATS),
                ("The returned list is a view of the stream elements in encounter order. ")
                        .repeat(TYPICAL_EVIDENCE_REPEATS));
        String sentence = "O método toList retorna uma lista imutável com os elementos na ordem do stream.";
        checker.isSupported(sentence, evidence);

        long start = System.nanoTime();
        for (int i = 0; i < TIMED_RUNS; i++) {
            checker.isSupported(sentence, evidence);
        }
        long millisPerSentence = (System.nanoTime() - start) / 1_000_000 / TIMED_RUNS;

        LOG.info("nli: {} ms por frase (2 trechos citados de ~370 caracteres, max-tokens 256)", millisPerSentence);
        assertThat(millisPerSentence).isLessThan(LatencyBudget.millis(MAX_MILLIS_PER_SENTENCE));
    }

    /** O pior caso da resposta: a frase contra os 5 trechos do contexto, cada um no teto de 1500 caracteres. */
    @Test
    void sentenceAgainstFiveCeilingSizedChunksIsLogged() {
        List<String> evidence = new ArrayList<>();
        for (int i = 0; i < CONTEXT_CHUNKS; i++) {
            evidence.add(("Trecho de documentação número " + i + " sobre listas imutáveis. ").repeat(CEILING_REPEATS));
        }
        String sentence = "O método toList retorna uma lista imutável com os elementos na ordem do stream.";
        checker.isSupported(sentence, evidence);

        long start = System.nanoTime();
        for (int i = 0; i < TIMED_RUNS; i++) {
            checker.isSupported(sentence, evidence);
        }
        long millisPerSentence = (System.nanoTime() - start) / 1_000_000 / TIMED_RUNS;

        LOG.info(
                "nli: {} ms por frase (5 trechos citados no teto de ~1500 caracteres, max-tokens 256)",
                millisPerSentence);
        assertThat(millisPerSentence).isLessThan(LatencyBudget.millis(WORST_CASE_MAX_MILLIS));
    }

    @Test
    void corruptTokenizerMakesTheConstructorThrowAfterTheSessionWasOpened() throws Exception {
        Path dir = ScratchDirs.create("sage-nli");
        try {
            Files.writeString(dir.resolve("tokenizer.json"), "isto não é um tokenizer");
            NliFiles original = filesFromProperty();
            NliFiles files = new NliFiles(original.model(), dir.resolve("tokenizer.json"), original.config());

            assertThatThrownBy(() -> new OnnxNliChecker(files, PROPERTIES)).isInstanceOf(RuntimeException.class);
        } finally {
            ScratchDirs.delete(dir);
        }
    }

    @Test
    void corruptModelMakesTheConstructorThrow() throws Exception {
        Path dir = ScratchDirs.create("sage-nli");
        try {
            Files.writeString(dir.resolve("nli.onnx"), "isto não é um modelo");
            NliFiles original = filesFromProperty();
            NliFiles files = new NliFiles(dir.resolve("nli.onnx"), original.tokenizer(), original.config());

            assertThatThrownBy(() -> new OnnxNliChecker(files, PROPERTIES)).isInstanceOf(RuntimeException.class);
        } finally {
            ScratchDirs.delete(dir);
        }
    }

    @Test
    void configWithoutEntailmentLabelIsRejected() throws Exception {
        Path dir = ScratchDirs.create("sage-nli");
        try {
            Files.writeString(dir.resolve("config.json"), "{\"id2label\":{\"0\":\"a\",\"1\":\"b\",\"2\":\"c\"}}");
            NliFiles original = filesFromProperty();
            NliFiles files = new NliFiles(original.model(), original.tokenizer(), dir.resolve("config.json"));

            assertThatThrownBy(() -> new OnnxNliChecker(files, PROPERTIES))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("entailment");
        } finally {
            ScratchDirs.delete(dir);
        }
    }

    @Test
    void duplicateLabelsAreRejected() throws Exception {
        Path dir = ScratchDirs.create("sage-nli");
        try {
            Files.writeString(
                    dir.resolve("config.json"),
                    "{\"id2label\":{\"0\":\"entailment\",\"1\":\"Entailment\",\"2\":\"contradiction\"}}");
            NliFiles original = filesFromProperty();
            NliFiles files = new NliFiles(original.model(), original.tokenizer(), dir.resolve("config.json"));

            assertThatThrownBy(() -> new OnnxNliChecker(files, PROPERTIES))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("duplicado");
        } finally {
            ScratchDirs.delete(dir);
        }
    }

    @Test
    void checkingAfterCloseFailsCleanly() {
        OnnxNliChecker closed = new OnnxNliChecker(filesFromProperty(), PROPERTIES);
        closed.close();

        List<String> evidence = List.of(EVIDENCE);
        assertThatThrownBy(() -> closed.isSupported("frase", evidence)).isInstanceOf(IllegalStateException.class);
        closed.close();
    }
}
