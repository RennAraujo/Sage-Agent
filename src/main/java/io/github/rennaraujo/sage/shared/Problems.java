package io.github.rennaraujo.sage.shared;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/**
 * Fábrica dos {@link ProblemDetail} (RFC 9457) de toda a API. Só tipo, título e status: nada de detalhe interno, nome
 * de exceção, stack trace ou conteúdo de documento.
 */
public final class Problems {

    private static final String TYPE_PREFIX = "urn:sage:problem:";

    private Problems() {}

    public static ProblemDetail of(HttpStatusCode status) {
        HttpStatus known = HttpStatus.resolve(status.value());
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setType(URI.create(TYPE_PREFIX + slug(known)));
        problem.setTitle(title(known));
        return problem;
    }

    private static String slug(HttpStatus status) {
        if (status == null) {
            return "error";
        }
        return switch (status) {
            case BAD_REQUEST -> "bad-request";
            case UNAUTHORIZED -> "unauthorized";
            case FORBIDDEN -> "forbidden";
            case NOT_FOUND -> "not-found";
            case METHOD_NOT_ALLOWED -> "method-not-allowed";
            case NOT_ACCEPTABLE -> "not-acceptable";
            case CONTENT_TOO_LARGE -> "payload-too-large";
            case UNSUPPORTED_MEDIA_TYPE -> "unsupported-media-type";
            case UNPROCESSABLE_CONTENT -> "unprocessable-content";
            case CONFLICT -> "conflict";
            case TOO_MANY_REQUESTS -> "rate-limited";
            default -> status.is5xxServerError() ? "internal-error" : "error";
        };
    }

    private static String title(HttpStatus status) {
        if (status == null) {
            return "Erro";
        }
        return switch (status) {
            case BAD_REQUEST -> "Requisição inválida";
            case UNAUTHORIZED -> "Autenticação necessária";
            case FORBIDDEN -> "Acesso negado";
            case NOT_FOUND -> "Não encontrado";
            case METHOD_NOT_ALLOWED -> "Método não permitido";
            case NOT_ACCEPTABLE -> "Formato de resposta não aceito";
            case CONTENT_TOO_LARGE -> "Conteúdo grande demais";
            case UNSUPPORTED_MEDIA_TYPE -> "Tipo de conteúdo não suportado";
            case UNPROCESSABLE_CONTENT -> "Conteúdo não processável";
            case CONFLICT -> "Conflito";
            case TOO_MANY_REQUESTS -> "Muitas requisições";
            default -> status.is5xxServerError() ? "Erro interno" : "Erro";
        };
    }
}
