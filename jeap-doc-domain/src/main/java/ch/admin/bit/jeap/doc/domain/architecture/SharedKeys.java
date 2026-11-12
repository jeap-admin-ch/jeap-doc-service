package ch.admin.bit.jeap.doc.domain.architecture;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * The foreign keys an entry standing for several tables shows: the ones every one of those tables declares.
 * <p>
 * <b>Foreign keys are not part of a shape</b> - see {@link ShardFamilies#signatureOf} - so the tables of one
 * entry can declare different ones. An entry that showed the keys of one of its tables would be wrong about
 * the others, and silently so: a family of two whose second table points elsewhere would lose that arrow.
 * <p>
 * Two keys are the same where their columns are and where the tables they point at resolve to the same entry.
 * That is what keeps the keys of real partitions: a child partition points at the counterpart partition of
 * its parent, which is a different table and the same entry. It is only known once every entry is, so this
 * runs after the grouping and the collapsing.
 *
 * @param kept    the keys every table declares, as the first table spells them
 * @param partial how many keys only some of the tables declare
 */
record SharedKeys(List<SchemaForeignKey> kept, int partial) {

    /**
     * @param tables  the tables one entry stands for, the one whose spelling the entry keeps first
     * @param entryOf the name of the entry that stands for a table
     */
    static SharedKeys of(List<SchemaTable> tables, UnaryOperator<String> entryOf) {
        Set<String> shared = null;
        Set<String> declared = new HashSet<>();
        for (SchemaTable table : tables) {
            Set<String> keys = new HashSet<>();
            table.foreignKeys().forEach(key -> keys.add(identityOf(key, entryOf)));
            declared.addAll(keys);
            if (shared == null) {
                shared = keys;
            } else {
                shared.retainAll(keys);
            }
        }
        Set<String> everywhere = shared == null ? Set.of() : shared;
        List<SchemaForeignKey> kept = tables.isEmpty() ? List.of() : tables.getFirst().foreignKeys().stream()
                .filter(key -> everywhere.contains(identityOf(key, entryOf)))
                .toList();
        return new SharedKeys(kept, declared.size() - everywhere.size());
    }

    private static String identityOf(SchemaForeignKey key, UnaryOperator<String> entryOf) {
        String target = key.referencedTableName() == null ? "" : entryOf.apply(key.referencedTableName());
        return (String.join(",", key.columnNames()) + "->" + target + "("
                + String.join(",", key.referencedColumnNames()) + ")").toLowerCase(Locale.ROOT);
    }
}
