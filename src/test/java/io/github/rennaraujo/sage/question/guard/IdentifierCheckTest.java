package io.github.rennaraujo.sage.question.guard;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class IdentifierCheckTest {

    private static final List<String> EVIDENCE = List.of(
            "Stream.toList() returns an unmodifiable List. Use Collectors.toCollection(ArrayList::new) for a mutable"
                    + " one.",
            "Set spring.http.client.connect-timeout and use @MockitoBean to replace a bean.");

    @Test
    void knownIdentifiersPassWhateverTheCaseOrPunctuation() {
        assertThat(IdentifierCheck.allKnown("O stream.tolist() devolve uma lista não modificável.", EVIDENCE))
                .isTrue();
        assertThat(IdentifierCheck.allKnown("Defina spring.http.client.connect-timeout.", EVIDENCE))
                .isTrue();
        assertThat(IdentifierCheck.allKnown("Troque o bean com @MockitoBean.", EVIDENCE))
                .isTrue();
    }

    @Test
    void anIdentifierNoCitedChunkHasFailsTheSentence() {
        assertThat(IdentifierCheck.allKnown("O Stream.toImmutableList() devolve uma lista.", EVIDENCE))
                .isFalse();
        assertThat(IdentifierCheck.allKnown("Use spring.http.client.timeout.read para o timeout.", EVIDENCE))
                .isFalse();
        assertThat(IdentifierCheck.allKnown("Troque o bean com @MockBean.", EVIDENCE))
                .isFalse();
    }

    @Test
    void sentencesWithoutIdentifiersAndVersionNumbersAreUntouched() {
        assertThat(IdentifierCheck.allKnown(
                        "No Spring Boot 3.5 e 4.1 o prazo é de 30 minutos, i.e. meia hora.", EVIDENCE))
                .isTrue();
    }

    @Test
    void callSyntaxAndMarkdownDecorationDoNotMakeAnIdentifierUnknown() {
        assertThat(IdentifierCheck.allKnown(
                        "Use toList() para coletar.", List.of("Call the toList method of a stream.")))
                .isTrue();
        assertThat(IdentifierCheck.allKnown(
                        "O Stream.toList() devolve lista.", List.of("Stream.toList returns a List.")))
                .isTrue();
        assertThat(IdentifierCheck.allKnown("Use List.of() ou List.of(a, b).", List.of("List.of(...) builds a list.")))
                .isTrue();
        assertThat(IdentifierCheck.allKnown("Use `ArrayList::new` aqui.", List.of("toCollection(ArrayList.new)")))
                .isTrue();
        assertThat(IdentifierCheck.allKnown("Use ArrayList.new aqui.", List.of("Use `ArrayList::new` here.")))
                .isTrue();
    }

    @Test
    void anIdentifierInsideParenthesesOfTheEvidenceIsStillFound() {
        assertThat(IdentifierCheck.allKnown("Veja Stream.toList.", List.of("(see Stream.toList) for details")))
                .isTrue();
    }
}
