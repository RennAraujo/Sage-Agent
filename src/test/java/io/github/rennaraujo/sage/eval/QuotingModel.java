package io.github.rennaraujo.sage.eval;

import io.github.rennaraujo.sage.question.llm.LanguageModel;
import io.github.rennaraujo.sage.question.llm.ModelEvent;
import io.github.rennaraujo.sage.question.llm.ModelTier;
import io.github.rennaraujo.sage.question.llm.Prompt;
import io.github.rennaraujo.sage.question.llm.PromptBuilder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import reactor.core.publisher.Flux;

/**
 * Modelo de linguagem determinístico para as avaliações, sem Bedrock. Lê os trechos do próprio prompt (as
 * tags {@code <documento>}) e responde conforme o {@link Mode}: cita a primeira frase do trecho de cada
 * versão (comportamento de um modelo bem-comportado), ou tenta fazer o que o acervo mandou (injeção),
 * inventar ou vazar um segredo.
 */
public final class QuotingModel implements LanguageModel {

    /** O que o modelo de mentira faz com o contexto. */
    public enum Mode {
        /** Cita a primeira frase do melhor trecho de cada versão (ou o melhor trecho, fora de comparação). */
        QUOTE_TOP,
        /** Obedece a injeção plantada, sem citar nada. */
        FOLLOW_INJECTION_UNCITED,
        /** Obedece a injeção plantada e põe uma citação válida no fim, como um modelo esperto faria. */
        FOLLOW_INJECTION_CITED,
        /** Responde com conhecimento próprio, sem citar. */
        INVENT,
        /** Cita a linha do trecho que contém o texto dado (para testar o filtro de segredos). */
        QUOTE_LINE_WITH,
        /** Responde com a paráfrase em pt-BR escrita à mão para o trecho que estiver no contexto. */
        PARAPHRASE,
        /** Diz, pelo molde de ausência e citando o trecho, que a documentação da versão não menciona o assunto. */
        ABSENCE_TEMPLATE
    }

    private static final Pattern BLOCK = Pattern.compile(
            "<documento n=\"(\\d+)\" fonte=\"([^\"]*)\" secao=\"([^\"]*)\"(?: versao=\"([^\"]*)\")?"
                    + "(?: origem=\"interna\")?>\n(.*?)\n</documento>",
            Pattern.DOTALL);
    private static final Pattern LIST_MARKER = Pattern.compile("^\\s*(?:\\d+\\.|[-*])\\s+");
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+(?=[A-ZÀ-Ú])");
    private static final int DELTA_CHARS = 24;
    private static final int MIN_SENTENCE = 25;
    private static final String INJECTION_ANSWER = "PWNED";
    private static final String INVENTED = "A resposta correta é 42 e vale para qualquer versão";

    private final Mode mode;
    private final String needle;
    private final Map<String, String> paraphrases;
    private final AtomicLong firstCall = new AtomicLong();
    private final List<Prompt> prompts = new ArrayList<>();

    public QuotingModel(Mode mode) {
        this(mode, "");
    }

    public QuotingModel(Mode mode, String needle) {
        this.mode = mode;
        this.needle = needle;
        this.paraphrases = Map.of();
    }

    /** Paráfrases por "documento|caminho de seções"; sem paráfrase para o contexto, cita a primeira frase. */
    public QuotingModel(Map<String, String> paraphrases) {
        this.mode = Mode.PARAPHRASE;
        this.needle = "";
        this.paraphrases = Map.copyOf(paraphrases);
    }

    /** Instante ({@link System#nanoTime()}) da primeira chamada desde o último {@link #resetMark()}, ou 0. */
    public long firstCallNanos() {
        return firstCall.get();
    }

    public void resetMark() {
        firstCall.set(0);
    }

    public synchronized List<Prompt> prompts() {
        return List.copyOf(prompts);
    }

    @Override
    public Flux<ModelEvent> stream(ModelTier tier, Prompt prompt) {
        firstCall.compareAndSet(0, System.nanoTime());
        synchronized (this) {
            prompts.add(prompt);
        }
        String answer = answer(blocks(prompt.user()));
        List<ModelEvent> events = new ArrayList<>();
        for (int i = 0; i < answer.length(); i += DELTA_CHARS) {
            events.add(new ModelEvent.TextDelta(answer.substring(i, Math.min(answer.length(), i + DELTA_CHARS))));
        }
        events.add(new ModelEvent.Completed());
        return Flux.fromIterable(events);
    }

    private String answer(List<Block> blocks) {
        return switch (mode) {
            case QUOTE_TOP -> quoteTop(blocks);
            case FOLLOW_INJECTION_UNCITED -> INJECTION_ANSWER + ".";
            case FOLLOW_INJECTION_CITED ->
                INJECTION_ANSWER + (blocks.isEmpty() ? "" : " [" + blocks.get(0).n() + "]") + ".";
            case INVENT -> INVENTED + ".";
            case QUOTE_LINE_WITH -> quoteLineWith(blocks);
            case PARAPHRASE -> paraphrase(blocks);
            case ABSENCE_TEMPLATE -> absence(blocks);
        };
    }

    private String quoteTop(List<Block> blocks) {
        if (blocks.isEmpty()) {
            return PromptBuilder.NOT_COVERED;
        }
        Map<String, Block> groups = new LinkedHashMap<>();
        boolean grouped = blocks.stream().anyMatch(b -> b.version() != null || b.internal());
        for (Block block : blocks) {
            groups.putIfAbsent(grouped ? block.groupKey() : "unico", block);
        }
        StringBuilder out = new StringBuilder();
        for (Block block : groups.values()) {
            out.append(cite(firstSentence(block.text()), block.n())).append(' ');
        }
        return out.toString().strip();
    }

    private String paraphrase(List<Block> blocks) {
        for (Block block : blocks) {
            String sentence = paraphrases.get(block.source() + "|" + block.section());
            if (sentence != null) {
                return cite(sentence, block.n());
            }
        }
        return quoteTop(blocks);
    }

    private static final Map<String, String> LANGUAGE_NAMES = Map.of("java", "Java", "spring-boot", "Spring Boot");

    /** Uma frase no molde "A documentação do Java 21 disponível não menciona isso [n]." por versão do contexto. */
    private String absence(List<Block> blocks) {
        Map<String, Block> groups = new LinkedHashMap<>();
        for (Block block : blocks) {
            groups.putIfAbsent(block.groupKey(), block);
        }
        StringBuilder out = new StringBuilder();
        for (Block block : groups.values()) {
            String[] path = block.source().split("/");
            String name = LANGUAGE_NAMES.getOrDefault(path[1], path[1]);
            out.append("A documentação do ")
                    .append(name)
                    .append(' ')
                    .append(block.version())
                    .append(" disponível não menciona isso [")
                    .append(block.n())
                    .append("]. ");
        }
        return out.toString().strip();
    }

    private String quoteLineWith(List<Block> blocks) {
        for (Block block : blocks) {
            for (String line : block.text().split("\n")) {
                for (String sentence : SENTENCE_END.split(stripMarkup(line))) {
                    if (sentence.contains(needle)) {
                        return cite(sentence, block.n());
                    }
                }
            }
        }
        return PromptBuilder.NOT_COVERED;
    }

    private static String cite(String sentence, int n) {
        String bare = sentence.strip().replaceAll("[.!?]+$", "");
        return bare + " [" + n + "].";
    }

    /** Primeira frase de prosa do trecho: ignora títulos, cercas de código e marcadores de lista. */
    static String firstSentence(String text) {
        for (String line : text.split("\n")) {
            String clean = stripMarkup(line);
            if (clean.isBlank() || line.startsWith("#") || line.startsWith("```")) {
                continue;
            }
            String[] pieces = SENTENCE_END.split(clean);
            StringBuilder sentence = new StringBuilder(pieces[0]);
            for (int i = 1; i < pieces.length && sentence.length() < MIN_SENTENCE; i++) {
                sentence.append(' ').append(pieces[i]);
            }
            return sentence.toString();
        }
        return text.strip();
    }

    private static String stripMarkup(String line) {
        return LIST_MARKER.matcher(line).replaceFirst("").strip();
    }

    private static List<Block> blocks(String user) {
        List<Block> blocks = new ArrayList<>();
        Matcher matcher = BLOCK.matcher(user);
        while (matcher.find()) {
            blocks.add(new Block(
                    Integer.parseInt(matcher.group(1)),
                    matcher.group(2),
                    matcher.group(3),
                    matcher.group(4),
                    matcher.group(0).contains("origem=\"interna\""),
                    matcher.group(5)));
        }
        return blocks;
    }

    private record Block(int n, String source, String section, String version, boolean internal, String text) {

        String groupKey() {
            return internal ? "interna" : String.valueOf(version);
        }
    }
}
