package ch.admin.bit.jeap.doc.domain.architecture;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One database schema as a page shows it: what is documented, what the diagram draws, what the list carries.
 * <p>
 * <b>Built once at the top of a page and read from there.</b> Every set below is derived from
 * {@link DatabaseSchema#documentedTables()}, which filters, collapses and sorts on every call - and one render
 * of one page asks for it about a dozen times, directly and through the counts, the diagram and the list. On a
 * schema of 6583 tables that is a dozen sorts and a dozen groupings of 33 000 columns.
 * <p>
 * A class rather than a record because it holds a lookup map for the diagram's arrows, and because it is a
 * derived view rather than data: two of them over the same schema are the same page, and nothing compares them.
 * <p>
 * <b>Per page is the right scope.</b> Holding the collapsed schemas of a whole landscape between builds would
 * amortise the work over a run, and would also hold every replicated schema in the JVM beside the Node process
 * that needs the container's memory - which is why the artifacts are joined per system while generating in the
 * first place.
 */
public final class DocumentedSchema {

    private final DatabaseSchema schema;
    private final List<SchemaTable> documented;
    private final List<SchemaTable> drawn;
    private final List<SchemaTable> listed;

    /**
     * The drawn tables by folded name, answering with the table's <b>own</b> spelling: a PlantUML code is
     * case-sensitive, so an arrow drawn with a foreign key's spelling grows a second, empty box instead.
     */
    private final Map<String, String> drawnByFoldedName;

    private DocumentedSchema(DatabaseSchema schema, List<SchemaTable> documented, List<SchemaTable> drawn,
                             List<SchemaTable> listed) {
        this.schema = schema;
        this.documented = documented;
        this.drawn = drawn;
        this.listed = listed;
        this.drawnByFoldedName = new HashMap<>();
        for (SchemaTable table : drawn) {
            drawnByFoldedName.put(folded(table.name()), table.name());
        }
    }

    /**
     * <b>The diagram is drawn out of the listed entries</b>, so a box on it always has an entry a reader can
     * look up. Both bounds are the page's, and the smaller of the two is what the diagram gets.
     *
     * @param maxDrawn  how many tables the entity relationship diagram may draw
     * @param maxListed how many entries the page may write with their columns
     */
    public static DocumentedSchema of(DatabaseSchema schema, int maxDrawn, int maxListed) {
        List<SchemaTable> documented = schema.documentedTables();
        List<SchemaTable> listed = DatabaseSchema.listedFrom(documented, maxListed);
        return new DocumentedSchema(schema, documented, DatabaseSchema.drawnFrom(listed, maxDrawn), listed);
    }

    /** Every table worth documenting, sorted by name, with each family of partitions as one entry. */
    public List<SchemaTable> documented() {
        return documented;
    }

    /** The entries the diagram draws. */
    public List<SchemaTable> drawn() {
        return drawn;
    }

    /** How many tables the published schema holds, before anything was hidden or collapsed. */
    public int rawTableCount() {
        return schema.rawTableCount();
    }

    /** How many entries there are to document, after collapsing. */
    public int documentedCount() {
        return documented.size();
    }

    /** How many of those entries stand for a family of partitions rather than for one table. */
    public int collapsedFamilies() {
        return (int) documented.stream().filter(table -> table.shards() != null).count();
    }

    /** The entries the page writes with their columns. */
    public List<SchemaTable> listed() {
        return listed;
    }

    /**
     * How many of the <b>listed</b> entries the diagram leaves out - the diagram's own bound and nothing else.
     * <p>
     * Counted against the list rather than against everything documented because the diagram is drawn out of
     * the listed entries: a page whose list is bounded draws fewer boxes than there are entries without the
     * diagram's bound ever being reached, and a note blaming the diagram for that would name the wrong cause.
     * What the list leaves out is {@link #notListed()}.
     */
    public int notDrawn() {
        return listed.size() - drawn.size();
    }

    /** How many documented entries the page does not write. Nothing a reader can open carries those. */
    public int notListed() {
        return documented.size() - listed.size();
    }

    /** Which machinery tables this schema has, so the page can name the ones it left out. */
    public List<String> hiddenTables() {
        return schema.hiddenTables();
    }

    public boolean isEmpty() {
        return documented.isEmpty();
    }

    /**
     * The drawn entity a foreign key points at, or null where it points at nothing the diagram has.
     * <p>
     * A key into a shard - {@code doc_meta_1607} - finds the entity its family was collapsed into, so that
     * collapsing a schema does not silently drop every arrow in it.
     */
    public String drawnEntityNameOf(String referencedTableName) {
        if (referencedTableName == null) {
            return null;
        }
        String drawnName = drawnByFoldedName.get(folded(referencedTableName));
        if (drawnName != null) {
            return drawnName;
        }
        String family = ShardFamilies.familyNameOf(referencedTableName);
        return family == null ? null : drawnByFoldedName.get(folded(family));
    }

    private static String folded(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
