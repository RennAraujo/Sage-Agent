package io.github.rennaraujo.sage.auth;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.EstimationProbe;
import io.github.bucket4j.TimeMeter;
import io.github.rennaraujo.sage.shared.RateLimitExceededException;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Limite por chave de API e por ação, em memória (um bucket Bucket4j para cada par). Os baldes são recarregados por
 * inteiro a cada minuto. O mapa só cresce com nomes de chaves configuradas, então não precisa de expurgo. Com mais de
 * uma instância da aplicação o limite vale por instância; para um limite global faltaria um backend compartilhado.
 */
public final class RateLimiter {

    private static final Duration WINDOW = Duration.ofMinutes(1);
    private static final long TOKENS_PER_REQUEST = 1;

    private final RateLimitProperties properties;
    private final TimeMeter timeMeter;
    private final ConcurrentMap<BucketId, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimiter(RateLimitProperties properties) {
        this(properties, TimeMeter.SYSTEM_NANOTIME);
    }

    RateLimiter(RateLimitProperties properties, TimeMeter timeMeter) {
        this.properties = properties;
        this.timeMeter = timeMeter;
    }

    /** Consome uma permissão. {@code false} se o limite do minuto já foi usado. */
    public boolean tryConsume(String keyName, RateLimitedAction action) {
        return bucket(keyName, action).tryConsume(TOKENS_PER_REQUEST);
    }

    /** Quanto falta para a próxima permissão; {@link Duration#ZERO} se já há uma disponível. */
    public Duration retryAfter(String keyName, RateLimitedAction action) {
        EstimationProbe probe = bucket(keyName, action).estimateAbilityToConsume(TOKENS_PER_REQUEST);
        return probe.canBeConsumed() ? Duration.ZERO : Duration.ofNanos(probe.getNanosToWaitForRefill());
    }

    /** Atalho para os endpoints: consome ou lança {@link RateLimitExceededException} (vira 429 com Retry-After). */
    public void require(String keyName, RateLimitedAction action) {
        if (!tryConsume(keyName, action)) {
            throw new RateLimitExceededException(retryAfter(keyName, action));
        }
    }

    private Bucket bucket(String keyName, RateLimitedAction action) {
        return buckets.computeIfAbsent(new BucketId(keyName, action), id -> newBucket(action));
    }

    private Bucket newBucket(RateLimitedAction action) {
        int limit = properties.limitFor(action);
        return Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(limit)
                        .refillIntervally(limit, WINDOW)
                        .build())
                .withCustomTimePrecision(timeMeter)
                .build();
    }

    private record BucketId(String keyName, RateLimitedAction action) {}
}
