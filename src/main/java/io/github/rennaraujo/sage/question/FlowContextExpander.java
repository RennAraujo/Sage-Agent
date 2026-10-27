package io.github.rennaraujo.sage.question;

import io.github.rennaraujo.sage.document.DocumentId;
import io.github.rennaraujo.sage.document.InvalidDocumentException;
import io.github.rennaraujo.sage.document.Source;
import io.github.rennaraujo.sage.search.Chunk;
import io.github.rennaraujo.sage.search.ScoredChunk;
import io.github.rennaraujo.sage.search.SearchIndex;
import io.github.rennaraujo.sage.upload.UploadService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Amplia o contexto de uma pergunta de fluxo. Os trechos soltos da busca mostram um passo; o fluxo está no documento.
 * Para cada documento interno entre os melhores trechos, entra o documento inteiro se ele tem até
 * {@code sage.flow.max-doc-chars} caracteres; senão, a seção de nível 2 do melhor trecho dele ("Faturamento >
 * Emissão", com tudo que vem abaixo). Depois seguem-se, com profundidade 1, os wiki-links desse texto
 * ({@code [[slug]]}, {@code [[interno/app/slug]]}, com {@code |apelido} e {@code #título} ignorados), no máximo
 * {@code max-linked-docs} documentos, só da mesma aplicação: nunca de uma fonte para outra, nunca de outra aplicação,
 * e link que não resolve para um documento do índice é ignorado.
 *
 * <p>Teto total: o texto somado nunca passa de {@code max-context-chars}. O que não cabe é cortado na fronteira de
 * trecho (os trechos já nascem partidos em títulos e parágrafos pelo chunker, então o corte cai entre blocos): o
 * documento ligado entra com o começo dele, e o que sobrar de orçamento menor que o primeiro trecho é descartado. Se a
 * primeira entrada sozinha passa do teto (um trecho único gigante), ela é cortada no último limite de linha ou espaço
 * que cabe; as seguintes que não cabem são descartadas. O teto é, assim, estrito.
 * Documentos ligados não têm pergunta para escolher "a melhor seção" (o contrato recebe só os trechos), por isso o
 * começo é a escolha. Os documentos já encontrados pela busca têm prioridade sobre os ligados.
 *
 * <p>Trechos públicos passam como vieram. Cada documento vira um único {@link ScoredChunk} (com o score do melhor
 * trecho dele; os ligados têm score 0, já que a busca não os trouxe), então a numeração {@code [n]} e o guard
 * continuam sendo por entrada. A expansão só mexe no prompt: a pergunta, a busca e o reranker não a veem.
 */
public final class FlowContextExpander {

    private static final Pattern WIKI_LINK = Pattern.compile("\\[\\[([^\\[\\]\\n]{1,200})]]");
    private static final String BLOCK_SEPARATOR = "\n\n";
    private static final String PATH_SEPARATOR = " > ";
    private static final String INTERNAL_PREFIX = "interno/";
    private static final int SECTION_LEVELS = 2;
    private static final int EXPLICIT_SEGMENTS = 3;

    private final SearchIndex index;
    private final FlowProperties properties;

    public FlowContextExpander(SearchIndex index, FlowProperties properties) {
        this.index = index;
        this.properties = properties;
    }

    public List<ScoredChunk> expand(List<ScoredChunk> top) {
        // Um slot por documento, na ordem do melhor trecho; trechos públicos ficam como estão.
        Map<DocumentId, List<ScoredChunk>> internal = new LinkedHashMap<>();
        List<Object> order = new ArrayList<>();
        for (ScoredChunk hit : top) {
            if (hit.chunk().source() != Source.INTERNAL) {
                order.add(hit);
                continue;
            }
            internal.computeIfAbsent(hit.chunk().documentId(), id -> {
                        order.add(id);
                        return new ArrayList<>();
                    })
                    .add(hit);
        }
        int budget = properties.maxContextChars();
        List<ScoredChunk> result = new ArrayList<>();
        List<ScoredChunk> expandedPrimary = new ArrayList<>();
        for (Object slot : order) {
            if (slot instanceof ScoredChunk pass) {
                Optional<ScoredChunk> fitted = fit(pass, budget, result.isEmpty());
                fitted.ifPresent(result::add);
                budget -= fitted.map(c -> c.chunk().text().length()).orElse(0);
                continue;
            }
            DocumentId id = (DocumentId) slot;
            ScoredChunk wanted = primary(id, internal.get(id), budget)
                    .orElseGet(() -> internal.get(id).get(0));
            Optional<ScoredChunk> fitted = fit(wanted, budget, result.isEmpty());
            if (fitted.isPresent()) {
                result.add(fitted.get());
                expandedPrimary.add(fitted.get());
                budget -= fitted.get().chunk().text().length();
            }
        }
        Set<DocumentId> taken = new LinkedHashSet<>(internal.keySet());
        int linked = 0;
        for (DocumentId target : linksOf(expandedPrimary)) {
            if (linked >= properties.maxLinkedDocs() || budget <= 0) {
                break;
            }
            if (!taken.add(target)) {
                continue;
            }
            List<Chunk> chunks = index.chunksOf(target);
            if (chunks.isEmpty()) {
                continue;
            }
            Optional<ScoredChunk> entry = linkedEntry(chunks, Math.min(budget, properties.maxDocChars()));
            if (entry.isPresent()) {
                result.add(entry.get());
                budget -= entry.get().chunk().text().length();
                linked++;
            }
        }
        return List.copyOf(result);
    }

    /**
     * Garante o teto: a entrada cabe, ou (sendo a primeira, que não pode faltar) é cortada no último limite de
     * parágrafo, linha ou espaço dentro do orçamento, ou (não sendo a primeira) é descartada. Um trecho único maior que
     * o teto só existe com bloco de código grande, que o chunker não parte; aqui ele é cortado porque o teto vale.
     */
    private static Optional<ScoredChunk> fit(ScoredChunk entry, int budget, boolean first) {
        Chunk c = entry.chunk();
        if (c.text().length() <= budget) {
            return Optional.of(entry);
        }
        if (!first || budget <= 0) {
            return Optional.empty();
        }
        String cut = c.text().substring(0, budget);
        int boundary = Math.max(cut.lastIndexOf('\n'), cut.lastIndexOf(' '));
        String text = (boundary > 0 ? cut.substring(0, boundary) : cut).stripTrailing();
        return Optional.of(new ScoredChunk(
                new Chunk(
                        c.id(),
                        c.documentId(),
                        c.source(),
                        c.language(),
                        c.version(),
                        c.headingPath(),
                        text,
                        c.ordinal(),
                        c.reviewed()),
                entry.score()));
    }

    /** O documento inteiro, ou a seção de nível 2 do melhor trecho, ou o que couber; vazio se o índice não o tem. */
    private Optional<ScoredChunk> primary(DocumentId id, List<ScoredChunk> hits, int budget) {
        List<Chunk> all = index.chunksOf(id);
        if (all.isEmpty()) {
            return Optional.empty();
        }
        ScoredChunk best = hits.stream()
                .max((a, b) -> Double.compare(a.score(), b.score()))
                .orElseThrow();
        int limit = Math.max(1, Math.min(budget, properties.maxDocChars()));
        if (length(all) <= limit) {
            return Optional.of(merge(all, topHeading(all), best.score()));
        }
        String key = sectionKey(best.chunk().headingPath());
        List<Chunk> section = all.stream()
                .filter(c -> sectionKey(c.headingPath()).equals(key))
                .toList();
        if (section.isEmpty()) {
            section = List.of(best.chunk());
        }
        List<Chunk> fitted = fitAround(section, best.chunk(), limit);
        return Optional.of(merge(fitted, key, best.score()));
    }

    /** O começo do documento ligado, o que couber em {@code limit} cortando entre trechos. */
    private static Optional<ScoredChunk> linkedEntry(List<Chunk> all, int limit) {
        if (length(all) <= limit) {
            return Optional.of(merge(all, topHeading(all), 0.0));
        }
        List<Chunk> head = new ArrayList<>();
        int size = 0;
        for (Chunk c : all) {
            int next = size + c.text().length() + (head.isEmpty() ? 0 : BLOCK_SEPARATOR.length());
            if (next > limit) {
                break;
            }
            head.add(c);
            size = next;
        }
        return head.isEmpty() ? Optional.empty() : Optional.of(merge(head, topHeading(head), 0.0));
    }

    /**
     * Seção maior que o limite: fica o melhor trecho e, a partir dele, os seguintes na ordem do documento, depois os
     * anteriores, até o limite. O trecho que a busca escolheu nunca fica de fora (mesmo que sozinho passe do limite,
     * que é o caso de um bloco de código maior que o teto do chunker).
     */
    private static List<Chunk> fitAround(List<Chunk> section, Chunk best, int limit) {
        int center = Math.max(0, section.indexOf(best));
        int lo = center;
        int hi = center;
        int size = section.get(center).text().length();
        boolean grew = true;
        while (grew) {
            grew = false;
            if (hi + 1 < section.size()) {
                int next = size
                        + BLOCK_SEPARATOR.length()
                        + section.get(hi + 1).text().length();
                if (next <= limit) {
                    hi++;
                    size = next;
                    grew = true;
                }
            }
            if (lo > 0) {
                int next = size
                        + BLOCK_SEPARATOR.length()
                        + section.get(lo - 1).text().length();
                if (next <= limit && !grew) {
                    lo--;
                    size = next;
                    grew = true;
                }
            }
        }
        return section.subList(lo, hi + 1);
    }

    /** Links, na ordem de aparição, dos textos que o modelo vai ver, já resolvidos para a mesma aplicação. */
    private List<DocumentId> linksOf(List<ScoredChunk> expanded) {
        Set<DocumentId> out = new LinkedHashSet<>();
        for (ScoredChunk entry : expanded) {
            Chunk chunk = entry.chunk();
            Matcher m = WIKI_LINK.matcher(chunk.text());
            while (m.find()) {
                resolve(m.group(1), chunk).ifPresent(out::add);
            }
        }
        return List.copyOf(out);
    }

    private static Optional<DocumentId> resolve(String raw, Chunk from) {
        String target = raw;
        int pipe = target.indexOf('|');
        if (pipe >= 0) {
            target = target.substring(0, pipe);
        }
        int hash = target.indexOf('#');
        if (hash >= 0) {
            target = target.substring(0, hash);
        }
        target = target.strip();
        if (target.toLowerCase(Locale.ROOT).endsWith(".md")) {
            target = target.substring(0, target.length() - ".md".length());
        }
        if (target.isBlank()) {
            return Optional.empty();
        }
        String app = from.documentId().value().split("/")[1];
        String[] parts = target.split("/", -1);
        String name;
        if (parts.length == 1) {
            name = parts[0];
        } else if (parts.length == EXPLICIT_SEGMENTS
                && INTERNAL_PREFIX.equals(parts[0].strip().toLowerCase(Locale.ROOT) + "/")
                && app.equals(parts[1].strip().toLowerCase(Locale.ROOT))) {
            name = parts[2];
        } else {
            return Optional.empty();
        }
        if (name.isBlank()) {
            return Optional.empty();
        }
        // Mesma função do upload: "Fluxo de Cobrança" vira "fluxo-de-cobranca", o nome com que o arquivo foi salvo.
        String path = INTERNAL_PREFIX + app + "/" + UploadService.slug(name + ".md");
        try {
            DocumentId id = DocumentId.of(path + ".md");
            return id.source() == Source.INTERNAL ? Optional.of(id) : Optional.empty();
        } catch (InvalidDocumentException e) {
            return Optional.empty();
        }
    }

    private static ScoredChunk merge(List<Chunk> parts, String headingPath, double score) {
        Chunk first = parts.get(0);
        String text =
                String.join(BLOCK_SEPARATOR, parts.stream().map(Chunk::text).toList());
        boolean reviewed = parts.stream().allMatch(Chunk::reviewed);
        return new ScoredChunk(
                new Chunk(
                        first.id(),
                        first.documentId(),
                        first.source(),
                        first.language(),
                        first.version(),
                        headingPath,
                        text,
                        first.ordinal(),
                        reviewed),
                score);
    }

    private static int length(List<Chunk> chunks) {
        int total = 0;
        for (Chunk c : chunks) {
            total += c.text().length();
        }
        return total + BLOCK_SEPARATOR.length() * Math.max(0, chunks.size() - 1);
    }

    /** Título de nível 1 do documento (primeiro segmento do primeiro caminho não vazio). */
    private static String topHeading(List<Chunk> chunks) {
        for (Chunk c : chunks) {
            if (!c.headingPath().isEmpty()) {
                return c.headingPath().split(Pattern.quote(PATH_SEPARATOR), 2)[0];
            }
        }
        return "";
    }

    /** Os dois primeiros níveis do caminho; caminho de um nível só é a própria chave (a introdução do documento). */
    private static String sectionKey(String headingPath) {
        String[] parts = headingPath.split(Pattern.quote(PATH_SEPARATOR));
        if (parts.length <= SECTION_LEVELS) {
            return headingPath;
        }
        return parts[0] + PATH_SEPARATOR + parts[1];
    }
}
