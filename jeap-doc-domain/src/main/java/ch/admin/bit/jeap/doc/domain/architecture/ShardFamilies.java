package ch.admin.bit.jeap.doc.domain.architecture;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Replaces the shards of a partitioned table with the one table they logically are.
 * <p>
 * A component that partitions by day or by tenant publishes a table per partition: one schema in the estate
 * has 6583 tables, of which 6321 are shards of 260. Documented one by one they make a page nobody can read
 * and, measured, an hour and a half of build time.
 * <p>
 * <b>A family is recognised by two signals, and the second is what makes it safe.</b> The stem alone would
 * merge {@code order_v1} and {@code order_v2}, or an {@code audit_2024} kept deliberately apart from
 * {@code audit_2025}, and the reader would never learn that their columns differ. So every table of a stem
 * has to share one column signature, which shards do because the database generated them from one definition.
 * <p>
 * <b>A stem whose tables do not all agree is left alone entirely</b>, rather than collapsed into the groups
 * they do form. Two groups of one stem would both be called {@code <stem>_*}, and a page with two rows of
 * that name is worse than a page with the tables themselves. What bounds the cost of such a schema is the
 * list limit, not this - so there is nothing to be won here by guessing.
 * <p>
 * Nothing here infers what a table is <i>for</i>. The published schema carries a name and columns, and these
 * two signals are what can be read off them; PostgreSQL knows the answer properly in {@code pg_inherits}, and
 * the day the publisher passes it on this class goes away.
 */
final class ShardFamilies {

    /**
     * How many tables of one stem make a family.
     * <p>
     * Measured rather than chosen: of the 810 published schemas at 200 tables or fewer, exactly one holds a
     * single shard-suffixed table, and every one of the 20 above 200 is 78-99.7% shards. Any value from 3 to
     * 20 partitions this estate identically, so five guards against a schema that does not exist yet.
     */
    private static final int MIN_FAMILY = 5;

    /** What a collapsed family is named: the stem, and a postfix saying at a glance that this is not one table. */
    private static final String FAMILY_POSTFIX = "*";

    private static final char SEPARATOR = '_';

    /** {@code _202609}, {@code _20260904} - a partition per month or per day. */
    private static final Pattern DATE = Pattern.compile("(19|20)\\d{2}(0[1-9]|1[0-2])([0-3]\\d)?");

    /** {@code _54}, {@code _1607} - a partition per tenant or per bucket. */
    private static final Pattern ORDINAL = Pattern.compile("\\d+");

    /** {@code _ym110}, {@code _ch71} - an ordinal with a short tag in front of it. */
    private static final Pattern TAGGED_ORDINAL = Pattern.compile("[a-z]{1,4}\\d+");

    private ShardFamilies() {
    }

    /**
     * The same tables with each family replaced by one representative, and everything else untouched.
     * <p>
     * In no particular order - the caller sorts by name, and a representative sorts under its stem so that a
     * collapsed family lands where a reader looks for it.
     */
    static List<SchemaTable> collapse(List<SchemaTable> tables) {
        Map<String, List<SchemaTable>> byStem = new LinkedHashMap<>();
        List<SchemaTable> collapsed = new ArrayList<>(tables.size());
        for (SchemaTable table : tables) {
            String stem = stemOf(table.name());
            if (stem == null) {
                collapsed.add(table);
            } else {
                byStem.computeIfAbsent(stem, ignored -> new ArrayList<>()).add(table);
            }
        }
        for (Map.Entry<String, List<SchemaTable>> stem : byStem.entrySet()) {
            List<SchemaTable> shards = stem.getValue();
            if (isFamily(shards)) {
                collapsed.add(representativeOf(stem.getKey(), shards));
            } else {
                collapsed.addAll(shards);
            }
        }
        return collapsed;
    }

    /** Enough tables of one stem, and every one of them the same table partitioned. */
    private static boolean isFamily(List<SchemaTable> shards) {
        if (shards.size() < MIN_FAMILY) {
            return false;
        }
        String signature = signatureOf(shards.getFirst());
        return shards.stream().allMatch(shard -> signature.equals(signatureOf(shard)));
    }

    /**
     * What a collapsed family carrying this table would be called, or null where the name is no shard.
     * <p>
     * The diagram needs it: a foreign key into {@code doc_meta_1607} has to find the entity
     * {@code doc_meta_*}, or the arrow is dropped as pointing at nothing drawn.
     */
    static String familyNameOf(String tableName) {
        String stem = stemOf(tableName);
        return stem == null ? null : stem + SEPARATOR + FAMILY_POSTFIX;
    }

    /**
     * One table standing for the whole family. Its columns, primary key and foreign keys are the family's -
     * identical across it, which is what the signature established - and it carries what it stands for so
     * that the page can say so.
     */
    private static SchemaTable representativeOf(String stem, List<SchemaTable> shards) {
        List<String> suffixes = shards.stream().map(ShardFamilies::suffixOf).sorted(ShardFamilies::compareSuffix)
                .toList();
        SchemaTable first = shards.getFirst();
        return new SchemaTable(stem + SEPARATOR + FAMILY_POSTFIX, first.columns(), first.primaryKeyColumns(),
                first.foreignKeys(),
                new Shards(shards.size(), SEPARATOR + suffixes.getFirst(), SEPARATOR + suffixes.getLast()));
    }

    /**
     * The name without its partition key, or null where the last segment is not one.
     * <p>
     * The date pattern is tested before the plain ordinal, which would match it too - the answer is the same
     * either way, and testing it first is what says which of them a reader is looking at.
     */
    private static String stemOf(String tableName) {
        if (tableName == null) {
            return null;
        }
        int separator = tableName.lastIndexOf(SEPARATOR);
        if (separator <= 0 || separator == tableName.length() - 1) {
            return null;
        }
        String suffix = tableName.substring(separator + 1).toLowerCase(Locale.ROOT);
        boolean partitionKey = DATE.matcher(suffix).matches()
                               || ORDINAL.matcher(suffix).matches()
                               || TAGGED_ORDINAL.matcher(suffix).matches();
        return partitionKey ? tableName.substring(0, separator) : null;
    }

    private static String suffixOf(SchemaTable table) {
        return table.name().substring(table.name().lastIndexOf(SEPARATOR) + 1);
    }

    /**
     * The columns as one string rather than a hash. <b>Exact on purpose</b>: a collision would merge two
     * tables whose columns differ, which is the one thing this signal exists to prevent.
     */
    private static String signatureOf(SchemaTable table) {
        StringBuilder signature = new StringBuilder();
        for (SchemaColumn column : table.columns()) {
            signature.append(column.name()).append(':').append(column.type()).append(':')
                    .append(column.nullable()).append('\n');
        }
        return signature.toString();
    }

    /** Numerically where both are numbers, so that {@code 9} comes before {@code 10}. */
    private static int compareSuffix(String left, String right) {
        if (ORDINAL.matcher(left).matches() && ORDINAL.matcher(right).matches()
            && left.length() != right.length()) {
            return Integer.compare(left.length(), right.length());
        }
        return left.compareToIgnoreCase(right);
    }
}
