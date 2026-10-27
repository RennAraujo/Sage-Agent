package io.github.rennaraujo.sage.audit;

import io.github.rennaraujo.sage.crypto.EnvelopeCipher;
import io.github.rennaraujo.sage.storage.BlobStorage;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * Grava cada evento como um blob cifrado em {@code audit/<yyyy-MM-dd>/<uuid>} (data em UTC). O ator, o tipo e os
 * detalhes ficam só dentro do payload cifrado; a chave do blob tem apenas a data e um UUID aleatório. Um blob por
 * evento evita ler-modificar-gravar e perder registros em concorrência, ao custo de muitos objetos pequenos.
 *
 * <p>Se a gravação falhar, lança {@link AuditException} (quem chamou decide abortar a operação auditada). O log recebe
 * só o tipo do evento e a classe da falha: a mensagem da exceção original pode carregar o que se queria proteger.
 */
public final class EncryptedAuditLog implements AuditLog {

    private static final Logger LOG = LoggerFactory.getLogger(EncryptedAuditLog.class);
    private static final String PREFIX = "audit/";
    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    private final EnvelopeCipher cipher;
    private final BlobStorage blobs;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public EncryptedAuditLog(EnvelopeCipher cipher, BlobStorage blobs) {
        this.cipher = cipher;
        this.blobs = blobs;
    }

    @Override
    public void record(AuditEvent event) {
        try {
            byte[] payload = mapper.writeValueAsBytes(toJson(event));
            String key = PREFIX + DAY.format(event.at()) + "/" + UUID.randomUUID();
            blobs.put(key, cipher.encrypt(payload).toBytes());
        } catch (RuntimeException e) {
            LOG.error(
                    "Falha ao gravar evento de auditoria {} ({})",
                    event.type(),
                    e.getClass().getSimpleName());
            throw new AuditException();
        }
    }

    private static Map<String, Object> toJson(AuditEvent event) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("type", event.type().name());
        json.put("actor", event.actor());
        json.put("at", event.at().toString());
        json.put("details", event.details());
        return json;
    }
}
