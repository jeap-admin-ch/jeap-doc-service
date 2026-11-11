package ch.admin.bit.jeap.doc.domain.architecture;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * A group of tables an instance configures for one component: tables with the same structure under different
 * names, such as one table per entity and month, documented as one entry.
 * <p>
 * <b>The pattern selects, the shape decides.</b> Only the matching tables that share their columns and their
 * primary key are grouped - see {@link TableGroups}. A configured pattern is a claim about names, and a reader
 * shown one entry must not lose a table whose structure differs.
 *
 * @param name   the heading of the entry and the name of its box on the diagram
 * @param tables a regular expression over the whole table name, with a named group {@code entity} that says
 *               which entity a table belongs to, and an optional named group {@code partition}
 */
public record SchemaTableGroup(String name, Pattern tables) {

    static final String ENTITY = "entity";
    static final String PARTITION = "partition";

    /**
     * Compiles a configured group, or throws where it is not usable.
     * <p>
     * A name may not contain {@code *}, so a configured entry can never take the name of a partitioned table
     * that {@link ShardFamilies} wrote.
     */
    public static SchemaTableGroup of(String name, String tables) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("a group has no name");
        }
        if (name.contains("*")) {
            throw new IllegalArgumentException(
                    "the name '" + name + "' contains '*', which marks the partitioned tables grouped by name");
        }
        if (tables == null || tables.isBlank()) {
            throw new IllegalArgumentException("the group '" + name + "' has no pattern for its tables");
        }
        Pattern pattern;
        try {
            pattern = Pattern.compile(tables);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException(
                    "'" + tables + "' is not a regular expression: " + e.getDescription(), e);
        }
        if (!pattern.namedGroups().containsKey(ENTITY)) {
            throw new IllegalArgumentException(
                    "'" + tables + "' has no named group '" + ENTITY + "', which says what a table belongs to");
        }
        return new SchemaTableGroup(name, pattern);
    }

    /** The match over the whole table name, or null where the table is not one of this group's. */
    Matcher matching(String tableName) {
        Matcher matcher = tables.matcher(tableName);
        return matcher.matches() ? matcher : null;
    }

    boolean hasPartitions() {
        return tables.namedGroups().containsKey(PARTITION);
    }
}
