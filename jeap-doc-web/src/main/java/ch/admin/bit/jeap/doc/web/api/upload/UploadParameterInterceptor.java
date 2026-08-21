package ch.admin.bit.jeap.doc.web.api.upload;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;

/**
 * Rejects an upload that carries a query parameter the doc service does not know.
 * <p>
 * A typo in a doc workflow configuration must fail loudly instead of silently publishing something else than the
 * repository intended. The check runs before the parameters are bound to the handler, so a misspelled parameter
 * is reported as the typo it is instead of as the well-spelled one it hides.
 */
class UploadParameterInterceptor implements HandlerInterceptor {

    static final List<String> KNOWN_QUERY_PARAMETERS = List.of(
            "site", "type", "system", "component", "library", "template", "source-format", "location", "topic",
            "label", "source-repository", "source-revision", "source-ref", "source-timestamp", "version",
            "build-url", "generated-at");

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.getParameterMap().keySet().stream()
                .filter(parameterName -> !KNOWN_QUERY_PARAMETERS.contains(parameterName))
                .findFirst()
                .ifPresent(parameterName -> {
                    throw InvalidUploadException.unknown(parameterName, String.join(", ", KNOWN_QUERY_PARAMETERS));
                });
        return true;
    }
}
