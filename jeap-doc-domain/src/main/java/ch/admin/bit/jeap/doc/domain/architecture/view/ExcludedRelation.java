package ch.admin.bit.jeap.doc.domain.architecture.view;

import ch.admin.bit.jeap.doc.domain.architecture.RelationKind;
import ch.admin.bit.jeap.doc.domain.architecture.RelationPaths;
import ch.admin.bit.jeap.doc.domain.architecture.SystemRelation;

import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * One relation left out of the diagrams and the relations tables: the platform's own plumbing, such as every
 * component uploading its database schema to the architecture repository.
 * <p>
 * <b>All the fields given have to match, and one that is not given matches anything.</b> An entry naming only
 * a provider takes everything that reaches it; one naming a provider and a path takes that one operation.
 * <p>
 * <b>A name is a regular expression and a path is not.</b> A landscape repeats a name with a prefix, which is
 * what a pattern is for, and a component name carries no brace. A path does:
 * {@code /api/openapi/{systemComponentName}} is not a regular expression at all - Java reads the brace as the
 * start of a repetition - so the entry an operator writes first would stop the service. A path is compared
 * literally after {@link RelationPaths#normalised}, which is also what makes it match a relation that spells
 * the variable differently.
 */
public final class ExcludedRelation {

    private final Pattern consumer;
    private final Pattern provider;
    private final String method;
    private final String path;
    private final Pattern messageType;

    private ExcludedRelation(Pattern consumer, Pattern provider, String method, String path,
                             Pattern messageType) {
        this.consumer = consumer;
        this.provider = provider;
        this.method = method;
        this.path = path;
        this.messageType = messageType;
    }

    /**
     * One configured entry, or an exception where it cannot be one.
     * <p>
     * An entry with no field at all would take every relation of the landscape, and one giving a path or a
     * method together with a message type can never match - a relation carries one or the other. Both are
     * typos a page would report by silently missing something, so they are refused here and the startup says
     * so.
     */
    public static ExcludedRelation of(String consumer, String provider, String method, String path,
                                      String messageType) {
        String restPath = blankToNull(path);
        String verb = blankToNull(method);
        String message = blankToNull(messageType);
        if (blankToNull(consumer) == null && blankToNull(provider) == null && verb == null && restPath == null
            && message == null) {
            throw new IllegalArgumentException("an entry leaves out every relation of the landscape when it "
                                               + "names none of consumer, provider, method, path or "
                                               + "message-type");
        }
        if (message != null && (restPath != null || verb != null)) {
            throw new IllegalArgumentException("an entry names a message-type together with a "
                                               + (restPath != null ? "path" : "method")
                                               + ", and no relation carries both");
        }
        return new ExcludedRelation(compiled(consumer, "consumer"), compiled(provider, "provider"),
                verb == null ? null : verb.strip().toUpperCase(Locale.ROOT),
                restPath == null ? null : RelationPaths.normalised(restPath),
                compiled(message, "message-type"));
    }

    /** Whether this entry takes the given relation off the diagrams and the tables. */
    public boolean matches(SystemRelation relation) {
        if (relation == null) {
            return false;
        }
        boolean rest = relation.kind() == RelationKind.REST_API;
        // A path or a method is about a REST call, a message type about a message. An entry naming neither is
        // about everything between its two components.
        if ((path != null || method != null) && !rest) {
            return false;
        }
        if (messageType != null && rest) {
            return false;
        }
        return matches(consumer, relation.consumer())
               && matches(provider, relation.provider())
               && matchesMethod(relation.method())
               && matchesPath(relation.path())
               && matches(messageType, relation.messageType());
    }

    /**
     * Whether the entry names this component at either end, which its own page says.
     * <p>
     * <b>Names it, rather than matches it.</b> An end this entry leaves out matches every relation, and an
     * entry about a path across the whole landscape is nobody's to report - only a component the entry points
     * at carries the sentence.
     */
    public boolean namesComponent(String component) {
        return (consumer != null && matches(consumer, component))
               || (provider != null && matches(provider, component));
    }

    private static boolean matches(Pattern pattern, String value) {
        return pattern == null || (value != null && pattern.matcher(value).matches());
    }

    private boolean matchesMethod(String value) {
        return method == null || (value != null && method.equalsIgnoreCase(value.strip()));
    }

    private boolean matchesPath(String value) {
        return path == null || (value != null && path.equals(RelationPaths.normalised(value)));
    }

    private static Pattern compiled(String pattern, String field) {
        if (blankToNull(pattern) == null) {
            return null;
        }
        try {
            return Pattern.compile(pattern.strip());
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException(
                    "the " + field + " '" + pattern + "' is not a regular expression: " + e.getDescription(), e);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
