package ch.admin.bit.jeap.doc.web.api.upload;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;
import java.util.function.BiFunction;

/**
 * Rejects an upload that carries a query parameter the doc service does not know.
 * <p>
 * A typo in a doc workflow configuration must fail loudly instead of silently publishing something else than the
 * repository intended. The check runs before the parameters are bound to the handler, so a misspelled parameter
 * is reported as the typo it is instead of as the well-spelled one it hides.
 * <p>
 * Which parameters are known depends on what is being uploaded, so every kind of upload registers this
 * interceptor on its own path with its own list.
 */
public class UploadParameterInterceptor implements HandlerInterceptor {

    private final List<String> knownParameters;
    private final BiFunction<String, String, RuntimeException> rejection;

    /**
     * @param knownParameters the query parameters the endpoint accepts
     * @param rejection       creates the exception for an unknown parameter, from the parameter name and the
     *                        comma-separated list of the known ones
     */
    public UploadParameterInterceptor(List<String> knownParameters,
                                      BiFunction<String, String, RuntimeException> rejection) {
        this.knownParameters = List.copyOf(knownParameters);
        this.rejection = rejection;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.getParameterMap().keySet().stream()
                .filter(parameterName -> !knownParameters.contains(parameterName))
                .findFirst()
                .ifPresent(parameterName -> {
                    throw rejection.apply(parameterName, String.join(", ", knownParameters));
                });
        return true;
    }
}
