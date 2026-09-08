package ch.admin.bit.jeap.doc.domain.template;

import ch.admin.bit.jeap.doc.domain.architecture.ApiGroup;
import ch.admin.bit.jeap.doc.domain.architecture.ApiOperation;
import ch.admin.bit.jeap.doc.domain.architecture.RestApiOverview;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Which paths of a REST specification the documentation describes.
 * <p>
 * <b>What this exists for is the actuator.</b> Every jEAP service publishes the operational endpoints the
 * platform needs - health, metrics, the AppConfig refresh - and they are in its specification. They are not
 * what a reader of the architecture documentation is looking for, and on a small service they outnumber the
 * operations that are.
 * <p>
 * A list of patterns rather than a rule about the actuator, because the paths a service keeps out of its
 * documentation are the service's business: a component with a management context path of its own, or an
 * internal API published on the same specification, is the same question with a different answer.
 * <p>
 * <b>Applied when a page is written, not when a specification is replicated.</b> The excluded paths are a
 * rendering decision, so changing them takes effect on the next build rather than on the next import.
 */
public final class DocumentedApiPaths {

    /** Everything is documented. What an instance that configures no exclusions gets. */
    public static final DocumentedApiPaths ALL = new DocumentedApiPaths(List.of());

    private final List<Pattern> excluded;

    private DocumentedApiPaths(List<Pattern> excluded) {
        this.excluded = excluded;
    }

    /**
     * Compiles the configured patterns, or throws where one of them is not a regular expression.
     * <p>
     * <b>A pattern has to match the whole path.</b> {@code /actuator} then means that one path and nothing
     * else, which is what someone writing a list of paths expects - a substring search would make
     * {@code /health} exclude {@code /api/health-reports} too.
     */
    public static DocumentedApiPaths excluding(List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) {
            return ALL;
        }
        List<Pattern> compiled = new ArrayList<>(patterns.size());
        for (String pattern : patterns) {
            try {
                compiled.add(Pattern.compile(pattern));
            } catch (PatternSyntaxException e) {
                throw new IllegalArgumentException(
                        "'" + pattern + "' is not a regular expression: " + e.getDescription(), e);
            }
        }
        return new DocumentedApiPaths(List.copyOf(compiled));
    }

    /** Whether a path is one the documentation describes. A null path is documented; nothing else knows it. */
    public boolean documents(String path) {
        if (path == null) {
            return true;
        }
        return excluded.stream().noneMatch(pattern -> pattern.matcher(path).matches());
    }

    public boolean excludesNothing() {
        return excluded.isEmpty();
    }

    /**
     * The same overview with the excluded operations gone, and with any group they emptied gone too - an empty
     * table promises part of an API that is not there.
     */
    public RestApiOverview documented(RestApiOverview api) {
        if (api == null || excludesNothing()) {
            return api;
        }
        List<ApiGroup> groups = new ArrayList<>();
        for (ApiGroup group : api.groups()) {
            List<ApiOperation> operations = group.operations().stream()
                    .filter(operation -> documents(operation.path()))
                    .toList();
            if (!operations.isEmpty()) {
                groups.add(new ApiGroup(group.name(), group.description(), operations));
            }
        }
        return new RestApiOverview(api.version(), api.serverUrl(), groups);
    }
}
