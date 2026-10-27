package io.github.rennaraujo.sage.gap;

import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Lacunas da documentação interna (só EDITOR; a regra fica no {@code SecurityConfig}). */
@RestController
@RequestMapping("/api/lacunas")
class GapController {

    static final int DEFAULT_LIMIT = 100;
    static final int MAX_LIMIT = 500;

    private final GapRegistry registry;

    GapController(GapRegistry registry) {
        this.registry = registry;
    }

    @GetMapping
    List<GapResponse> list(@RequestParam(name = "limite", defaultValue = "" + DEFAULT_LIMIT) int limit) {
        int effective = Math.max(1, Math.min(limit, MAX_LIMIT));
        return registry.summarize().stream()
                .limit(effective)
                .map(g -> new GapResponse(g.question(), g.lastAskedAt(), g.times()))
                .toList();
    }

    /** Os nomes dos campos são o contrato com o mini chat. */
    record GapResponse(String pergunta, Instant ultimaVez, int vezes) {}
}
