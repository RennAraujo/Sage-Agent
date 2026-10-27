package io.github.rennaraujo.sage.gap;

import io.github.rennaraujo.sage.crypto.EncryptedBlob;
import io.github.rennaraujo.sage.crypto.EnvelopeCipher;
import io.github.rennaraujo.sage.storage.BlobStorage;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Perguntas que a documentação interna não soube responder. Cada ocorrência vira um blob cifrado em
 * {@code gaps/<uuid>} com a pergunta, o instante, a pergunta normalizada (para agrupar) e um HMAC-SHA256 do nome da
 * chave que perguntou, com um segredo da instalação guardado cifrado (nunca o nome).
 * Fica uma linha por ocorrência, sem ler-modificar-gravar, então gravações concorrentes não se perdem.
 *
 * <p>O crescimento é limitado por {@code sage.gaps.max-entries} (5000): passou disso, os registros mais antigos são
 * apagados na própria gravação. A alternativa, descartar repetições recentes e só contar, economizaria disco mas
 * perderia o "última vez" exato de cada ocorrência, e 5000 blobs pequenos custam pouco. O preço é que, numa pergunta
 * muito repetida, o contador ({@code vezes}) cobre só as ocorrências que ainda cabem no teto.
 *
 * <p>{@link #summarize()} decifra todos os blobs na primeira chamada; depois mantém as ocorrências em memória e o
 * resumo pronto, que é refeito a cada nova lacuna. Blob que não decifra ou não lê é pulado. Nada da pergunta vai para
 * log: só contagens e a classe da falha.
 */
public final class GapRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(GapRegistry.class);
    private static final String PREFIX = "gaps/";
    private static final String SECRET_KEY = "keys/gap-hmac";
    private static final String HMAC = "HmacSHA256";
    private static final int SECRET_BYTES = 32;
    private static final int ACTOR_HASH_CHARS = 16;
    private static final Pattern ACCENTS = Pattern.compile("\\p{M}+");
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");

    private final EnvelopeCipher cipher;
    private final BlobStorage blobs;
    private final GapProperties properties;
    private final Clock clock;
    private final JsonMapper mapper = JsonMapper.builder().build();

    private final SecureRandom random = new SecureRandom();
    private byte[] secret;
    private List<Occurrence> occurrences;
    private List<GapSummary> summary;

    public GapRegistry(EnvelopeCipher cipher, BlobStorage blobs, GapProperties properties, Clock clock) {
        this.cipher = cipher;
        this.blobs = blobs;
        this.properties = properties;
        this.clock = clock;
    }

    /** Grava uma ocorrência. Lança {@link GapException} se não conseguir; quem chama decide ignorar. */
    public synchronized void record(String question, String actor) {
        try {
            loaded();
            Instant at = clock.instant();
            String normalized = normalize(question);
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("question", question);
            json.put("normalized", normalized);
            json.put("at", at.toString());
            json.put("actorHash", actorHash(actor));
            String key = PREFIX + UUID.randomUUID();
            blobs.put(key, cipher.encrypt(mapper.writeValueAsBytes(json)).toBytes());
            occurrences.add(new Occurrence(key, question, normalized, at));
            summary = null;
            prune();
        } catch (RuntimeException e) {
            LOG.error("Falha ao gravar lacuna ({})", e.getClass().getSimpleName());
            throw new GapException();
        }
    }

    /**
     * Carrega as lacunas guardadas sem esperar a primeira pergunta; chamado em segundo plano na subida. Falha não
     * derruba nada: o próximo {@link #record} ou {@link #summarize} tenta de novo.
     */
    public synchronized void warm() {
        try {
            loaded();
        } catch (RuntimeException e) {
            LOG.warn("Pré-carga das lacunas falhou ({})", e.getClass().getSimpleName());
        }
    }

    /** Lacunas agrupadas pela pergunta normalizada: mais vezes primeiro, e a mais recente em caso de empate. */
    public synchronized List<GapSummary> summarize() {
        if (summary == null) {
            summary = build(loaded());
        }
        return summary;
    }

    private List<Occurrence> loaded() {
        if (occurrences == null) {
            secret = loadSecret();
            List<Occurrence> read = new ArrayList<>();
            int skipped = 0;
            for (String key : blobs.list(PREFIX)) {
                Optional<Occurrence> occurrence = read(key);
                if (occurrence.isPresent()) {
                    read.add(occurrence.get());
                } else {
                    skipped++;
                }
            }
            if (skipped > 0) {
                LOG.warn("Lacunas ilegíveis ignoradas: {}", skipped);
            }
            read.sort(Comparator.comparing(Occurrence::at));
            occurrences = read;
        }
        return occurrences;
    }

    private Optional<Occurrence> read(String key) {
        try {
            Optional<byte[]> stored = blobs.get(key);
            if (stored.isEmpty()) {
                return Optional.empty();
            }
            byte[] plain = cipher.decrypt(EncryptedBlob.fromBytes(stored.get()));
            JsonNode node = mapper.readTree(plain);
            return Optional.of(new Occurrence(
                    key,
                    node.get("question").asString(),
                    node.get("normalized").asString(),
                    Instant.parse(node.get("at").asString())));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * Apaga os mais antigos além do teto. Só sai da memória o que o armazém apagou: se o delete falha, a ocorrência
     * fica (e o blob também) e a poda é refeita na próxima gravação, sem deixar blob órfão que o próximo load traria
     * de volta.
     */
    private void prune() {
        while (occurrences.size() > properties.maxEntries()) {
            try {
                blobs.delete(occurrences.get(0).key());
            } catch (RuntimeException e) {
                LOG.warn("Falha ao apagar lacuna antiga ({})", e.getClass().getSimpleName());
                return;
            }
            occurrences.remove(0);
        }
    }

    /**
     * Segredo da instalação para o HMAC do ator, guardado cifrado em {@code keys/gap-hmac}. Um hash simples do nome
     * da chave de API se desfaria por dicionário (os nomes são poucos e previsíveis); com o segredo, quem lê só o
     * bucket não consegue testar candidatos.
     */
    private byte[] loadSecret() {
        Optional<byte[]> stored = blobs.get(SECRET_KEY);
        if (stored.isPresent()) {
            return cipher.decrypt(EncryptedBlob.fromBytes(stored.get()));
        }
        byte[] fresh = new byte[SECRET_BYTES];
        random.nextBytes(fresh);
        blobs.put(SECRET_KEY, cipher.encrypt(fresh).toBytes());
        return fresh;
    }

    private static List<GapSummary> build(List<Occurrence> all) {
        Map<String, List<Occurrence>> groups = new LinkedHashMap<>();
        for (Occurrence occurrence : all) {
            groups.computeIfAbsent(occurrence.normalized(), k -> new ArrayList<>())
                    .add(occurrence);
        }
        return groups.values().stream()
                .map(group -> {
                    Occurrence latest = group.stream()
                            .max(Comparator.comparing(Occurrence::at))
                            .orElseThrow();
                    return new GapSummary(latest.question(), latest.at(), group.size());
                })
                .sorted(Comparator.comparingInt(GapSummary::times)
                        .reversed()
                        .thenComparing(GapSummary::lastAskedAt, Comparator.reverseOrder()))
                .toList();
    }

    /** Minúsculas, sem acento nem pontuação, espaços colapsados: "Qual é o prazo?" e "qual e o prazo" agrupam. */
    static String normalize(String question) {
        String decomposed = Normalizer.normalize(question, Normalizer.Form.NFD);
        String plain = ACCENTS.matcher(decomposed).replaceAll("").toLowerCase(Locale.ROOT);
        return NON_WORD.matcher(plain).replaceAll(" ").strip();
    }

    private String actorHash(String actor) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(secret, HMAC));
            byte[] digest = mac.doFinal(actor.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, ACTOR_HASH_CHARS);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 indisponível", e);
        }
    }

    private record Occurrence(String key, String question, String normalized, Instant at) {}
}
