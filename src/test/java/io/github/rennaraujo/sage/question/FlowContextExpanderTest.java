package io.github.rennaraujo.sage.question;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.rennaraujo.sage.document.DocumentId;
import io.github.rennaraujo.sage.document.Source;
import io.github.rennaraujo.sage.search.Chunk;
import io.github.rennaraujo.sage.search.ScoredChunk;
import io.github.rennaraujo.sage.search.SearchIndex;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FlowContextExpanderTest {

    private static final FlowProperties DEFAULTS = new FlowProperties(12000, 3, 24000);

    private final SearchIndex index = mock(SearchIndex.class);

    /** Registra um documento no índice falso; cada par é (caminho de títulos, texto). */
    private List<Chunk> doc(String id, String... pathAndText) {
        List<Chunk> chunks = new ArrayList<>();
        for (int i = 0; i < pathAndText.length; i += 2) {
            chunks.add(chunk(id, i / 2, pathAndText[i], pathAndText[i + 1]));
        }
        when(index.chunksOf(DocumentId.of(id))).thenReturn(chunks);
        return chunks;
    }

    private static Chunk chunk(String id, int ordinal, String path, String text) {
        DocumentId docId = DocumentId.of(id);
        Source source = docId.source();
        String[] parts = id.split("/");
        return new Chunk(
                id + "#" + ordinal,
                docId,
                source,
                parts[1],
                source == Source.PUBLIC ? Optional.of(parts[2]) : Optional.empty(),
                path,
                text,
                ordinal,
                true);
    }

    private FlowContextExpander expander(FlowProperties properties) {
        return new FlowContextExpander(index, properties);
    }

    private static List<ScoredChunk> top(Chunk chunk) {
        return List.of(new ScoredChunk(chunk, 0.8));
    }

    private static int total(List<ScoredChunk> chunks) {
        return chunks.stream().mapToInt(c -> c.chunk().text().length()).sum();
    }

    private static List<String> ids(List<ScoredChunk> chunks) {
        return chunks.stream().map(c -> c.chunk().documentId().value()).toList();
    }

    @Test
    void smallDocExpandsWhole() {
        List<Chunk> faturamento = doc(
                "interno/portal-x/faturamento.md",
                "Faturamento",
                "Visão geral.",
                "Faturamento > Emissão",
                "A nota é emitida no fechamento.",
                "Faturamento > Cobrança",
                "A cobrança sai no dia seguinte.");

        List<ScoredChunk> out = expander(DEFAULTS).expand(top(faturamento.get(1)));

        assertThat(out).hasSize(1);
        Chunk whole = out.get(0).chunk();
        assertThat(whole.documentId().value()).isEqualTo("interno/portal-x/faturamento.md");
        assertThat(whole.text())
                .contains("Visão geral.", "A nota é emitida no fechamento.", "A cobrança sai no dia seguinte.");
        assertThat(whole.text().indexOf("Visão geral.")).isLessThan(whole.text().indexOf("A cobrança"));
        assertThat(whole.source()).isEqualTo(Source.INTERNAL);
        assertThat(out.get(0).score()).isEqualTo(0.8);
    }

    @Test
    void largeDocExpandsSection() {
        List<Chunk> grande = doc(
                "interno/portal-x/grande.md",
                "Grande > Emissão",
                "E".repeat(300),
                "Grande > Emissão > Detalhe",
                "F".repeat(300),
                "Grande > Cobrança",
                "C".repeat(300));

        List<ScoredChunk> out = expander(new FlowProperties(700, 3, 24000)).expand(top(grande.get(1)));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).chunk().text()).contains("E".repeat(300), "F".repeat(300));
        assertThat(out.get(0).chunk().text()).doesNotContain("C");
        assertThat(out.get(0).chunk().headingPath()).isEqualTo("Grande > Emissão");
    }

    @Test
    void followsWikiLinkDepthOneOnly() {
        List<Chunk> a = doc(
                "interno/portal-x/faturamento.md",
                "Faturamento",
                "Depois do fechamento veja [[cobranca]] e também [[cobranca#prazos]], [[estorno|como estornar]], "
                        + "[[interno/portal-x/conciliacao]], [[interno/outro-app/segredo]], "
                        + "[[publico/java/21/streams]], [[nao-existe]] e [[../../x]].");
        doc("interno/portal-x/cobranca.md", "Cobrança", "Cobrança manda o boleto. Veja [[profundo]].");
        doc("interno/portal-x/estorno.md", "Estorno", "Estorno devolve o valor.");
        doc("interno/portal-x/conciliacao.md", "Conciliação", "Conciliação confere o extrato.");
        doc("interno/portal-x/profundo.md", "Profundo", "Não deveria entrar.");
        doc("interno/outro-app/segredo.md", "Segredo", "Outra aplicação.");
        doc("publico/java/21/streams.md", "Streams", "Fonte pública.");
        when(index.chunksOf(DocumentId.of("interno/portal-x/nao-existe.md"))).thenReturn(List.of());

        List<ScoredChunk> out = expander(DEFAULTS).expand(top(a.get(0)));

        assertThat(ids(out))
                .containsExactly(
                        "interno/portal-x/faturamento.md",
                        "interno/portal-x/cobranca.md",
                        "interno/portal-x/estorno.md",
                        "interno/portal-x/conciliacao.md");
    }

    @Test
    void capsLinkedDocs() {
        List<Chunk> a = doc("interno/portal-x/a.md", "A", "Veja [[b]], [[c]], [[d]], [[e]].");
        doc("interno/portal-x/b.md", "B", "texto b");
        doc("interno/portal-x/c.md", "C", "texto c");
        doc("interno/portal-x/d.md", "D", "texto d");
        doc("interno/portal-x/e.md", "E", "texto e");

        List<ScoredChunk> out = expander(DEFAULTS).expand(top(a.get(0)));

        assertThat(ids(out))
                .containsExactly(
                        "interno/portal-x/a.md",
                        "interno/portal-x/b.md",
                        "interno/portal-x/c.md",
                        "interno/portal-x/d.md");
    }

    @Test
    void respectsContextCap() {
        List<Chunk> a = doc("interno/portal-x/a.md", "A", "x".repeat(300) + " [[b]] [[c]]");
        doc("interno/portal-x/b.md", "B > Um", "y".repeat(400), "B > Dois", "z".repeat(400));
        doc("interno/portal-x/c.md", "C", "w".repeat(200));

        List<ScoredChunk> out = expander(new FlowProperties(5000, 3, 1000)).expand(top(a.get(0)));

        assertThat(total(out)).isLessThanOrEqualTo(1000);
        // b não cabe inteiro (800 > 700 restantes): entra truncado na fronteira de trecho, só com a primeira parte.
        Chunk b = out.get(1).chunk();
        assertThat(b.documentId().value()).isEqualTo("interno/portal-x/b.md");
        assertThat(b.text()).contains("y").doesNotContain("z");
        assertThat(ids(out)).contains("interno/portal-x/c.md");
    }

    @Test
    void oversizeFirstEntryIsTruncatedToTheCap() {
        Chunk big = chunk("interno/portal-x/a.md", 0, "A", "Parágrafo de teste.\n\n".repeat(150));
        when(index.chunksOf(DocumentId.of("interno/portal-x/a.md"))).thenReturn(List.of(big));

        List<ScoredChunk> out = expander(new FlowProperties(5000, 3, 1000)).expand(top(big));

        assertThat(total(out)).isLessThanOrEqualTo(1000);
        assertThat(out).hasSize(1);
        assertThat(out.get(0).chunk().text()).startsWith("Parágrafo de teste.");
    }

    @Test
    void wikiLinkSlugIsNormalizedLikeTheUploadSlug() {
        List<Chunk> a = doc("interno/portal-x/a.md", "A", "Veja [[Fluxo de Cobrança]] e [[estorno_manual|x]].");
        doc("interno/portal-x/fluxo-de-cobranca.md", "Cobrança", "texto");
        doc("interno/portal-x/estorno-manual.md", "Estorno", "texto");

        List<ScoredChunk> out = expander(DEFAULTS).expand(top(a.get(0)));

        assertThat(ids(out))
                .containsExactly(
                        "interno/portal-x/a.md",
                        "interno/portal-x/fluxo-de-cobranca.md",
                        "interno/portal-x/estorno-manual.md");
    }

    @Test
    void publicChunksPassThroughAndNeverFollowLinks() {
        Chunk pub = chunk("publico/java/21/streams.md", 0, "Streams", "Veja [[interno/portal-x/a]] e [[outro]].");
        doc("interno/portal-x/a.md", "A", "texto interno");
        doc("publico/java/21/outro.md", "Outro", "pública");

        List<ScoredChunk> out = expander(DEFAULTS).expand(top(pub));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).chunk()).isSameAs(pub);
    }

    @Test
    void docMissingFromIndexKeepsOriginalChunk() {
        Chunk orphan = chunk("interno/portal-x/sumiu.md", 0, "Sumiu", "texto");
        when(index.chunksOf(any())).thenReturn(List.of());

        assertThat(expander(DEFAULTS).expand(top(orphan))).containsExactly(new ScoredChunk(orphan, 0.8));
    }

    @Test
    void severalTopChunksOfSameDocBecomeOneEntry() {
        List<Chunk> a = doc("interno/portal-x/a.md", "A > Um", "um", "A > Dois", "dois");
        List<ScoredChunk> hits = List.of(new ScoredChunk(a.get(1), 0.9), new ScoredChunk(a.get(0), 0.5));

        List<ScoredChunk> out = expander(DEFAULTS).expand(hits);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).score()).isEqualTo(0.9);
    }
}
