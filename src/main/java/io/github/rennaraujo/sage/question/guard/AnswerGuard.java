package io.github.rennaraujo.sage.question.guard;

import io.github.rennaraujo.sage.question.llm.PromptBuilder;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Filtro contra delírio, aplicado frase a frase durante o streaming. Só passa frase com citação válida. Bloco de código
 * só passa se vier logo depois de uma frase aprovada (é ela que o justifica e o cita), se estiver fechado e se não for
 * prosa disfarçada de código; cada bloco consome a frase que o justificou, então dois blocos seguidos precisam de duas
 * frases citadas. O que é descartado nunca chega ao usuário e só entra na contagem. Se nada passar, o
 * {@link #finish()} devolve o texto fixo de "não cobre". Uma instância por resposta; não é thread-safe.
 *
 * <p>Prosa disfarçada: bloco marcado como {@code text}, {@code markdown}, {@code md}, {@code plain}, {@code plaintext}
 * ou {@code txt} é sempre recusado, porque não é código e escaparia da exigência de citação; vale a primeira palavra do
 * info string ({@code text title="x"}, {@code {.text}}, {@code text,foo} e {@code text/plain} contam como
 * {@code text}). Bloco sem linguagem é recusado quando mais da metade das linhas não vazias termina em ponto,
 * exclamação ou interrogação, que é como prosa termina e código quase nunca. Blocos com linguagem de programação ou
 * {@code mermaid} não passam por essa heurística.
 *
 * <p>Numa resposta de comparação, frase que nomeia uma versão ou linguagem ("Java 8", "versão 21", "Spring Boot 3.5")
 * e afirma inexistência ou indisponibilidade ("não existe", "não está disponível", "não há suporte", "não é
 * suportado", "inexistente", "não possui") só passa no molde {@link PromptBuilder#ABSENCE_TEMPLATE}, "A documentação
 * do Java 8 disponível não menciona records [1].", e ainda precisa da citação. O acervo pode estar incompleto: o Sage
 * diz o que a documentação não traz, nunca que o recurso não existe. Eventos que a documentação afirma ("foi removido
 * no Java 11 [1]", "deixou de existir", "foi descontinuado", "foi introduzido") não são afirmação de ausência e passam
 * como qualquer frase citada. Frases de comportamento ("lança NoSuchFileException se o arquivo não existe [1]", "o
 * record não possui setters [1]") não nomeiam versão e passam em qualquer rota; fora de comparação a regra não roda.
 */
public final class AnswerGuard {

    private static final Set<String> PROSE_TAGS =
            Set.of("text", "markdown", "md", "plain", "plaintext", "plain_text", "txt");

    /**
     * Afirmação de que algo não existe ou não está disponível; roda sobre o texto sem acento e em minúsculas. Remoção e
     * descontinuação ficam de fora: são fatos que a documentação afirma, não conclusões tiradas do silêncio.
     */
    private static final Pattern ABSENCE = Pattern.compile("\\bnao (?:existe|existia|existem|esta disponivel|estava "
            + "disponivel|estao disponiveis|ha suporte|tem suporte|e suportad|sao suportad|possui|possuem)"
            + "|\\binexistente");

    /** O molde aceito, também sem acento: "a documentacao do <nome> <versao> disponivel nao menciona <assunto>". */
    private static final Pattern ABSENCE_TEMPLATE_SHAPE =
            Pattern.compile("^a documentacao d[oa] \\S.* disponivel nao menciona (\\S.*)$");

    private static final String MERMAID_TAG = "mermaid";

    private static final int FENCE_LENGTH = 3;

    private final SentenceSplitter splitter = new SentenceSplitter();
    /** Frase que nomeia uma linguagem pública ou uma versão; roda sobre o texto sem acento e em minúsculas. */
    private static final Pattern NAMES_VERSION_OR_LANGUAGE = Pattern.compile(
            "\\b(?:java|jdk|kotlin|spring(?:\\s+(?:boot|framework|security))?)\\b" + "|\\bvers(?:ao|oes|ion)\\s*\\d");

    private final CitationValidator validator;
    private final boolean comparison;
    private final boolean flow;
    private final SortedSet<Integer> cited = new TreeSet<>();
    private int accepted;
    private int dropped;
    private boolean previousAccepted;
    private boolean flowNotDetailed;

    public AnswerGuard(int contextSize) {
        this(contextSize, false);
    }

    /** {@code comparison} liga a regra de afirmação de ausência, que só vale nas respostas de comparação. */
    public AnswerGuard(int contextSize, boolean comparison) {
        this(contextSize, comparison, false);
    }

    /** {@code flow} libera a frase fixa de fluxo não detalhado, que só faz sentido na rota de fluxo. */
    public AnswerGuard(int contextSize, boolean comparison, boolean flow) {
        this.flow = flow;
        this.validator = new CitationValidator(contextSize);
        this.comparison = comparison;
    }

    /** Recebe um pedaço do texto do modelo e devolve as frases que ficaram completas e foram aprovadas. */
    public List<String> accept(String delta) {
        return judge(splitter.push(delta));
    }

    /** Fecha o stream: avalia o que sobrou e, se nada foi aprovado, devolve o texto de "não cobre". */
    public List<String> finish() {
        List<String> out = judge(splitter.flush());
        if (accepted == 0) {
            out.add(flowNotDetailed ? PromptBuilder.FLOW_NOT_DETAILED : PromptBuilder.NOT_COVERED);
        }
        return out;
    }

    /** {@code true} se a resposta acabou sendo a frase fixa de fluxo não detalhado (o modelo a deu e nada passou). */
    public boolean flowNotDetailed() {
        return flowNotDetailed && accepted == 0;
    }

    public int dropped() {
        return dropped;
    }

    /** Índices citados pelas frases aprovadas até agora, em ordem crescente. */
    public SortedSet<Integer> cited() {
        return Collections.unmodifiableSortedSet(cited);
    }

    /** Citações {@code [n]} de um texto, na ordem em que aparecem; colchetes em código inline não contam. */
    public static List<Integer> citationsIn(String text) {
        return CitationValidator.citations(text);
    }

    private List<String> judge(List<SentenceSplitter.Segment> segments) {
        List<String> out = new ArrayList<>();
        for (SentenceSplitter.Segment segment : segments) {
            String text = segment.text();
            if (segment.code()) {
                take(out, text, previousAccepted && segment.closed() && !disguisedProse(text) && !unsafeMermaid(text));
                previousAccepted = false;
            } else if (flow && PromptBuilder.FLOW_NOT_DETAILED.equals(text)) {
                // Mesma ideia do "não cobre": é a resposta prevista para fluxo que a documentação não detalha.
                flowNotDetailed = true;
                previousAccepted = false;
            } else if (PromptBuilder.NOT_COVERED.equals(text)) {
                // Resposta prevista para "não está nos documentos": o finish() a emite se nada mais passar.
                previousAccepted = false;
            } else {
                boolean ok = validator.valid(text) && !(comparison && unsupportedAbsence(text));
                if (ok) {
                    cited.addAll(CitationValidator.citations(text));
                }
                take(out, text, ok);
                previousAccepted = ok;
            }
        }
        return out;
    }

    private void take(List<String> out, String text, boolean ok) {
        if (ok) {
            out.add(text);
            accepted++;
        } else {
            dropped++;
        }
    }

    /** Bloco mermaid fora da lista de permissão ({@link MermaidAllowList}): a interface pode renderizá-lo. */
    static boolean unsafeMermaid(String block) {
        String[] lines = block.split("\n", 2);
        if (!MERMAID_TAG.equals(fenceTag(lines[0])) || lines.length < 2) {
            return false;
        }
        return !MermaidAllowList.allowed(block);
    }

    static boolean disguisedProse(String block) {
        String[] lines = block.split("\n", -1);
        String tag = fenceTag(lines[0]);
        if (PROSE_TAGS.contains(tag)) {
            return true;
        }
        if (!tag.isEmpty()) {
            return false;
        }
        int total = 0;
        int prose = 0;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty() || line.startsWith("```")) {
                continue;
            }
            total++;
            char last = line.charAt(line.length() - 1);
            if (last == '.' || last == '!' || last == '?') {
                prose++;
            }
        }
        return total > 0 && prose * 2 > total;
    }

    /**
     * Frase que nomeia versão ou linguagem e afirma ausência fora do molde "A documentação do X disponível não menciona
     * ...". Só é consultada em respostas de comparação.
     */
    static boolean unsupportedAbsence(String sentence) {
        String folded = Normalizer.normalize(sentence.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .strip();
        Matcher template = ABSENCE_TEMPLATE_SHAPE.matcher(folded);
        if (!NAMES_VERSION_OR_LANGUAGE.matcher(folded).find()) {
            return false;
        }
        if (template.matches()) {
            // O assunto em si não pode trazer outra afirmação de ausência ("... não menciona isso, mas não existe").
            return ABSENCE.matcher(template.group(1)).find();
        }
        return ABSENCE.matcher(folded).find();
    }

    /**
     * Linguagem do bloco: só a primeira palavra do info string, sem as chaves e o ponto da sintaxe de atributos. Assim
     * {@code text title="x"} e {@code {.text}} contam como {@code text}, e o modelo não escapa da lista de prosa só
     * acrescentando atributos.
     */
    private static String fenceTag(String openingLine) {
        String info = openingLine.substring(FENCE_LENGTH).strip().toLowerCase(Locale.ROOT);
        if (info.startsWith("{")) {
            info = info.substring(1).strip();
        }
        if (info.startsWith(".")) {
            info = info.substring(1);
        }
        String[] words = info.split("[\\s,{}/]+", 2);
        return words.length == 0 ? "" : words[0];
    }
}
