package ch.admin.bit.jeap.doc.domain.architecture.view;

import ch.admin.bit.jeap.doc.domain.architecture.SystemRelation;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Components left out of the diagrams and relations tables of other pages, such as a test double that
 * publishes other systems' events. Their own pages stay.
 * <p>
 * A pattern has to match the whole component name, like the excluded REST paths.
 */
public final class ViewExcludedComponents {

    /** Nothing is left out. */
    public static final ViewExcludedComponents NONE = new ViewExcludedComponents(List.of());

    private final List<Pattern> excluded;

    private ViewExcludedComponents(List<Pattern> excluded) {
        this.excluded = excluded;
    }

    /** Compiles the configured patterns, or throws where one of them is not a regular expression. */
    public static ViewExcludedComponents excluding(List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) {
            return NONE;
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
        return new ViewExcludedComponents(List.copyOf(compiled));
    }

    public boolean excludes(String component) {
        return component != null && excluded.stream().anyMatch(pattern -> pattern.matcher(component).matches());
    }

    /** Whether a relation has a left-out component at either end. */
    public boolean excludes(SystemRelation relation) {
        return excludes(relation.consumer()) || excludes(relation.provider());
    }

    public boolean excludesNothing() {
        return excluded.isEmpty();
    }
}
