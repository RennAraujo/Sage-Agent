package io.github.rennaraujo.sage.question;

import io.github.rennaraujo.sage.document.Source;
import io.github.rennaraujo.sage.question.AliasCatalog.PublicLanguage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Descobre fonte, linguagem, versões e tipo de uma pergunta, herdando o contexto da anterior em follow-ups. */
@Component
public final class QuestionAnalyzer {

    private static final Pattern FLOW =
            Pattern.compile("como funciona|qual (e )?o fluxo|passo a passo|fluxo de|explique o processo");
    private static final Pattern COMPARISON = Pattern.compile("diferenca|migrar|migracao|comparar|versus|\\bvs\\.?\\b"
            + "|existe (?:no|na|em)|exclusiv[oa]|disponivel (?:no|na|em|a partir)"
            + "|a partir (?:de qual|do|da) versao|foi removid[oa]|foi introduzid[oa]"
            + "|desde (?:o|a|qual) |em qual versao|qual versao");
    private static final Pattern GENERIC_VERSION = Pattern.compile("\\bvers(?:ao|ion)\\s*(\\d+(?:\\.\\d+)*)");
    private static final int MIN_VERSIONS_FOR_COMPARISON = 2;

    private final AliasCatalog catalog;

    public QuestionAnalyzer(AliasCatalog catalog) {
        this.catalog = catalog;
    }

    /**
     * Analisa a pergunta. Todas as regexes rodam sobre o texto normalizado (minúsculas, sem acento), então
     * "MIGRAÇÃO" e "migracao" são a mesma coisa.
     *
     * @throws IllegalArgumentException se {@code question} for nula
     */
    public QuestionAnalysis analyze(String question, Optional<QuestionAnalysis> previous) {
        if (question == null) {
            throw new IllegalArgumentException("A pergunta não pode ser nula");
        }
        String text = AliasCatalog.normalize(question);
        boolean internalHit = catalog.mentionsInternal(question);
        Optional<PublicPick> publicHit = pickPublic(text);
        List<String> genericVersions = distinct(allMatches(GENERIC_VERSION, text));

        if (!internalHit && publicHit.isEmpty()) {
            return noMatch(text, genericVersions, previous);
        }

        Set<Source> sources = EnumSet.noneOf(Source.class);
        Optional<String> language = Optional.empty();
        List<String> versions = List.of();
        if (internalHit) {
            sources.add(Source.INTERNAL);
        }
        if (publicHit.isPresent()) {
            sources.add(Source.PUBLIC);
            language = Optional.of(publicHit.get().language().id());
            versions = publicHit.get().versions();
            if (versions.isEmpty()) {
                versions = genericVersions;
            }
            if (versions.isEmpty()) {
                versions = previous.filter(p -> p.language()
                                .equals(Optional.of(publicHit.get().language().id())))
                        .map(QuestionAnalysis::versions)
                        .orElse(List.of());
            }
        }
        return new QuestionAnalysis(
                sources,
                language,
                versions,
                typeOf(text, versions),
                internalHit && publicHit.isPresent(),
                publicHit.isPresent());
    }

    private QuestionAnalysis noMatch(String text, List<String> genericVersions, Optional<QuestionAnalysis> previous) {
        if (previous.isPresent()) {
            QuestionAnalysis prev = previous.get();
            List<String> versions = genericVersions.isEmpty() ? prev.versions() : genericVersions;
            return new QuestionAnalysis(
                    prev.sources(),
                    prev.language(),
                    versions,
                    typeOf(text, versions),
                    prev.mixed(),
                    prev.language().isPresent());
        }
        return new QuestionAnalysis(
                EnumSet.of(Source.PUBLIC, Source.INTERNAL),
                Optional.empty(),
                genericVersions,
                typeOf(text, genericVersions),
                false,
                false);
    }

    private record PublicPick(PublicLanguage language, List<String> versions) {}

    /** Entre várias linguagens citadas, vale a que trouxe versão; empatando, a mencionada primeiro. */
    private Optional<PublicPick> pickPublic(String text) {
        record Candidate(PublicPick pick, int position) {}

        List<Candidate> candidates = new ArrayList<>();
        for (PublicLanguage lang : catalog.publicLanguages()) {
            List<String> versions = distinct(lang.versionsIn(text));
            boolean named = lang.mentionedIn(text);
            if (named || !versions.isEmpty()) {
                int position = named ? lang.firstMention(text) : Integer.MAX_VALUE;
                candidates.add(new Candidate(new PublicPick(lang, versions), position));
            }
        }
        return candidates.stream()
                .min(Comparator.comparing((Candidate c) -> c.pick().versions().isEmpty())
                        .thenComparingInt(Candidate::position))
                .map(Candidate::pick);
    }

    private static QuestionType typeOf(String question, List<String> versions) {
        if (FLOW.matcher(question).find()) {
            return QuestionType.FLOW;
        }
        if (versions.size() >= MIN_VERSIONS_FOR_COMPARISON
                || COMPARISON.matcher(question).find()) {
            return QuestionType.COMPARISON;
        }
        return QuestionType.LOOKUP;
    }

    private static List<String> allMatches(Pattern pattern, String text) {
        List<String> found = new ArrayList<>();
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            found.add(m.group(1));
        }
        return found;
    }

    private static List<String> distinct(List<String> values) {
        return List.copyOf(new LinkedHashSet<>(values));
    }
}
