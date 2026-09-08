package ch.admin.bit.jeap.doc.domain.architecture;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * The database schema a component published, as the architecture repository stores it.
 * <p>
 * The field names are the ones of the architecture repository's own published schema model, so the mapping in
 * the adapter renames nothing. Fields no page shows are left out: a table keeps the columns its primary key is
 * made of but not the name of the constraint.
 *
 * @param name    the database name
 * @param version the version the component declared for it
 * @param tables  the tables, in the order the upstream listed them
 */
public record DatabaseSchema(String name, String version, List<SchemaTable> tables) {

    /**
     * Tables that are the machinery of a schema rather than its content, matched ignoring case. They are on
     * neither the diagram nor the table list, and the page names them so that nothing is missing silently.
     * <p>
     * <b>Two names, not a rule.</b> They are the jEAP conventions - the Flyway history table of
     * {@code jeap-spring-boot-db-migration-starter} and ShedLock's own - and nothing here recognises what a
     * table is <i>for</i>: a component that renamed its Flyway history table keeps it on the diagram, and one
     * that owns a table of its own called {@code shedlock} does not get it documented. Both are named in
     * {@code docs/generation.md} so that neither is a surprise, and neither is worth a configuration knob for
     * as long as the page says which tables it left out.
     */
    private static final Set<String> INFRASTRUCTURE_TABLES = Set.of("flyway_schema_history", "shedlock");

    public DatabaseSchema {
        tables = tables == null ? List.of() : List.copyOf(tables);
    }

    /**
     * Every table worth documenting, sorted by name, with each family of partitions as one entry - see
     * {@link ShardFamilies}.
     * <p>
     * <b>Collapsing happens before anything is bounded</b>, and that ordering is the whole design: it turns
     * both limits from a truncation into a formality for every schema in the estate but two. Bounding first
     * would throw away 6321 shards and collapse nothing.
     * <p>
     * <b>Worked out on every call.</b> A page render needs it a dozen times over, so it is asked for once and
     * held - see {@link DocumentedSchema}.
     */
    public List<SchemaTable> documentedTables() {
        List<SchemaTable> visible = tables.stream()
                .filter(table -> table.name() != null && !isInfrastructure(table.name()))
                .toList();
        return ShardFamilies.collapse(visible).stream()
                .sorted(Comparator.comparing(SchemaTable::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /**
     * How many tables the published schema holds, before anything was hidden or collapsed.
     * <p>
     * On the page beside the collapsed count, because a reader has to be able to see that 6583 tables exist
     * and that most of them are partitions of 260 - rather than be shown 260 and told nothing.
     */
    public int rawTableCount() {
        return tables.size();
    }

    /** Which machinery tables this schema has, so the page can name the ones it left out. */
    public List<String> hiddenTables() {
        Set<String> hidden = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (SchemaTable table : tables) {
            if (table.name() != null && isInfrastructure(table.name())) {
                hidden.add(table.name());
            }
        }
        return List.copyOf(hidden);
    }

    /**
     * The tables the diagram draws.
     * <p>
     * Which ones are kept is decided by priority; the order they are drawn in is always the alphabet, so two
     * runs over one schema produce the same bytes. A table something has a foreign key into is kept first, so
     * that the arrows of the tables that are drawn still point at a box.
     *
     * @param maxTables how many tables the diagram may draw. Above it the page says how many were left out
     */
    public List<SchemaTable> drawnTables(int maxTables) {
        return drawnFrom(documentedTables(), maxTables);
    }

    /** How many documented entries the diagram leaves out. The table list carries them anyway. */
    public int notDrawn(int maxTables) {
        return documentedTables().size() - drawnTables(maxTables).size();
    }

    /**
     * The entries the page writes with their columns: the first {@code maxEntries} by name.
     * <p>
     * <b>This is the bound that makes the page's cost finite.</b> The diagram was always bounded and the list
     * never was, so one component's page carried 6583 tables and 33 527 rows of columns - measured at
     * 46 minutes of build time on an idle container and 1 h 31 m under load.
     * <p>
     * By name and not by priority, unlike the diagram: a reader looks a table up here, so the entries have to
     * be where the alphabet says. Above the bound the page says how many it did not write and links the
     * published schema, which carries all of them.
     *
     * @param maxEntries how many entries the page may write
     */
    public List<SchemaTable> listedTables(int maxEntries) {
        return listedFrom(documentedTables(), maxEntries);
    }

    /** How many documented entries the page does not write. Only the published schema carries those. */
    public int notListed(int maxEntries) {
        return documentedTables().size() - listedTables(maxEntries).size();
    }

    /** As {@link #drawnFrom}: the same rule over a list a page has already collapsed and sorted. */
    static List<SchemaTable> listedFrom(List<SchemaTable> documented, int maxEntries) {
        int room = Math.min(Math.max(maxEntries, 0), documented.size());
        return room == documented.size() ? documented : List.copyOf(documented.subList(0, room));
    }

    /**
     * The same rule over a list already collapsed and sorted, so that a page pays for the collapse once.
     * Package-private for {@link DocumentedSchema}, which is the only caller that has such a list.
     */
    static List<SchemaTable> drawnFrom(List<SchemaTable> documented, int maxTables) {
        int room = Math.min(Math.max(maxTables, 0), documented.size());
        if (room == documented.size()) {
            return documented;
        }
        Set<String> referenced = referencedNamesOf(documented);
        List<SchemaTable> byPriority = new ArrayList<>(documented);
        byPriority.sort(Comparator
                .comparingInt((SchemaTable table) -> referenced.contains(folded(table.name())) ? 0 : 1)
                .thenComparing(SchemaTable::name, String.CASE_INSENSITIVE_ORDER));
        return byPriority.subList(0, room).stream()
                .sorted(Comparator.comparing(SchemaTable::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /**
     * The names something has a foreign key into, folded for comparison - and where a key points into a
     * shard, the family that shard was collapsed into. Without that, keeping a referenced table on the
     * diagram would keep the wrong ones.
     */
    private static Set<String> referencedNamesOf(List<SchemaTable> documented) {
        Set<String> referenced = new HashSet<>();
        for (SchemaTable table : documented) {
            for (SchemaForeignKey key : table.foreignKeys()) {
                if (key.referencedTableName() != null) {
                    referenced.add(folded(key.referencedTableName()));
                    String family = ShardFamilies.familyNameOf(key.referencedTableName());
                    if (family != null) {
                        referenced.add(folded(family));
                    }
                }
            }
        }
        return referenced;
    }

    public boolean isEmpty() {
        return documentedTables().isEmpty();
    }

    private static boolean isInfrastructure(String tableName) {
        return INFRASTRUCTURE_TABLES.contains(folded(tableName));
    }

    private static String folded(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
