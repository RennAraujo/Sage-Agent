package io.github.rennaraujo.sage.question.guard;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;

/** Verificador de teste: decide por um predicado e guarda cada chamada (frase e trechos) para as asserções. */
final class FakeGroundednessChecker implements GroundednessChecker {

    record Call(String sentence, List<String> evidence) {}

    private final BiPredicate<String, List<String>> rule;
    private final List<Call> calls = new ArrayList<>();

    FakeGroundednessChecker(BiPredicate<String, List<String>> rule) {
        this.rule = rule;
    }

    static FakeGroundednessChecker alwaysSupported() {
        return new FakeGroundednessChecker((sentence, evidence) -> true);
    }

    static FakeGroundednessChecker neverSupported() {
        return new FakeGroundednessChecker((sentence, evidence) -> false);
    }

    /** Sustenta só frases que contêm {@code word}. */
    static FakeGroundednessChecker supportingSentencesWith(String word) {
        return new FakeGroundednessChecker((sentence, evidence) -> sentence.contains(word));
    }

    @Override
    public boolean isSupported(String sentence, List<String> evidence) {
        calls.add(new Call(sentence, List.copyOf(evidence)));
        return rule.test(sentence, evidence);
    }

    List<Call> calls() {
        return calls;
    }
}
