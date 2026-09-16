package ch.admin.bit.jeap.doc.domain.architecture.view;

import ch.admin.bit.jeap.doc.domain.architecture.SystemRelation;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * What the diagrams and the relations tables of other pages leave out: whole components, and single relations.
 * <p>
 * <b>The two answer two questions.</b> A component is left out where it is noise as a building block - a test
 * double that publishes other systems' events - and its box goes with its arrows. A relation is left out where
 * the traffic is the platform's plumbing rather than a component's architecture - every component uploading its
 * schemas to the architecture repository - and then <b>only the arrow goes</b>: the component keeps its box on
 * every picture that would draw it, and its own pages show what it exchanges.
 * <p>
 * Neither reaches a counterpart column. The callers of an operation and the publishers and consumers of a
 * message are read from the relations and the contracts rather than from a view, so a relation that is not
 * drawn is still named where a reader goes to ask who does this.
 */
public final class ViewExclusions {

    /** Nothing is left out. */
    public static final ViewExclusions NONE = new ViewExclusions(List.of(), List.of());

    private final List<Pattern> components;
    private final List<ExcludedRelation> relations;

    private ViewExclusions(List<Pattern> components, List<ExcludedRelation> relations) {
        this.components = components;
        this.relations = relations;
    }

    /** Compiles the configured component patterns, or throws where one of them is not a regular expression. */
    public static ViewExclusions excluding(List<String> patterns) {
        return excluding(patterns, List.of());
    }

    /** The same, with the relations left out beside the components. */
    public static ViewExclusions excluding(List<String> patterns, List<ExcludedRelation> relations) {
        List<Pattern> compiled = new ArrayList<>();
        for (String pattern : patterns == null ? List.<String>of() : patterns) {
            try {
                compiled.add(Pattern.compile(pattern));
            } catch (PatternSyntaxException e) {
                throw new IllegalArgumentException(
                        "'" + pattern + "' is not a regular expression: " + e.getDescription(), e);
            }
        }
        List<ExcludedRelation> excluded = relations == null ? List.of() : List.copyOf(relations);
        if (compiled.isEmpty() && excluded.isEmpty()) {
            return NONE;
        }
        return new ViewExclusions(List.copyOf(compiled), excluded);
    }

    /** Whether this component is left out of the views altogether, box and arrows. */
    public boolean excludesComponent(String component) {
        return component != null
               && components.stream().anyMatch(pattern -> pattern.matcher(component).matches());
    }

    /** Whether a relation is left out: one of its ends is, or an entry names the relation itself. */
    public boolean excludes(SystemRelation relation) {
        if (relation == null) {
            return false;
        }
        return excludesComponent(relation.consumer())
               || excludesComponent(relation.provider())
               || relations.stream().anyMatch(excluded -> excluded.matches(relation));
    }

    /**
     * Whether an entry names this component at either end, which is what its own page says: some of its
     * relations are left out elsewhere, and its own pages show what it exchanges.
     */
    public boolean excludesRelationsOf(String component) {
        return component != null
               && relations.stream().anyMatch(excluded -> excluded.namesComponent(component));
    }

    public boolean excludesNothing() {
        return components.isEmpty() && relations.isEmpty();
    }
}
