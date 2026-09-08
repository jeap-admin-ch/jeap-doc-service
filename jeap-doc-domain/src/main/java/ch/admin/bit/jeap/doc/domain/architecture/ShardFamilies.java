package ch.admin.bit.jeap.doc.domain.architecture;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
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
 * has to share its columns and its primary key, which shards do because the database generated them from
 * one definition.
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

    /** {@code _54}, {@code _1607}, {@code _20260904} - a partition per tenant, per bucket or per day. */
    private static final Pattern ORDINAL = Pattern.compile("\\d+");

    /** {@code _ym110}, {@code _ch71} - an ordinal with a short tag in front of it. */
    private static final Pattern TAGGED_ORDINAL = Pattern.compile("[a-z]{1,4}\\d+");

    /**
     * The order a family's suffixes are ranged in: the tag first, then the digits as the number they are.
     * <p>
     * <b>An extracted key rather than the strings themselves.</b> Comparing two all-digit suffixes by length
     * and everything else lexicographically is no order at all - it is not transitive, so it can both range
     * a family wrongly and end a sort with an exception. Reading the digits as a number also sorts a
     * zero-padded {@code 007} where seven belongs.
     */
    private static final Comparator<String> BY_PARTITION_KEY = Comparator
            .comparing(ShardFamilies::tagOf, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(ShardFamilies::numberOf)
            .thenComparing(Comparator.naturalOrder());

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
     * One table standing for the whole family. Its columns and its primary key are the family's - identical
     * across it, which is what the signature established - and it carries what it stands for so that the
     * page can say so.
     * <p>
     * Its foreign keys are the first shard's, because they are not compared - see {@link #signatureOf}. The
     * arrows the diagram draws for a family are therefore the ones of one of its shards.
     */
    private static SchemaTable representativeOf(String stem, List<SchemaTable> shards) {
        List<String> suffixes = shards.stream().map(ShardFamilies::suffixOf).sorted(BY_PARTITION_KEY)
                .toList();
        SchemaTable first = shards.getFirst();
        return new SchemaTable(stem + SEPARATOR + FAMILY_POSTFIX, first.columns(), first.primaryKeyColumns(),
                first.foreignKeys(),
                new Shards(shards.size(), SEPARATOR + suffixes.getFirst(), SEPARATOR + suffixes.getLast()));
    }

    /**
     * The name without its partition key, or null where the last segment is not one.
     * <p>
     * Any run of digits counts, with or without a short tag in front of it. Nothing validates a date here:
     * the published schema does not say what a suffix means, so {@code _202613} is as good a partition key
     * as {@code _202612}.
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
        boolean partitionKey = ORDINAL.matcher(suffix).matches()
                               || TAGGED_ORDINAL.matcher(suffix).matches();
        return partitionKey ? tableName.substring(0, separator) : null;
    }

    private static String suffixOf(SchemaTable table) {
        return table.name().substring(table.name().lastIndexOf(SEPARATOR) + 1);
    }

    /**
     * The columns and the primary key as one string rather than a hash. <b>Exact on purpose</b>: a collision
     * would merge two tables whose columns differ, which is the one thing this signal exists to prevent.
     * <p>
     * <b>The foreign keys are left out deliberately.</b> A manually sharded child points at the counterpart
     * shard of its parent, so comparing them would keep apart a family a reader wants as one row.
     */
    private static String signatureOf(SchemaTable table) {
        StringBuilder signature = new StringBuilder();
        for (SchemaColumn column : table.columns()) {
            signature.append(column.name()).append(':').append(column.type()).append(':')
                    .append(column.nullable()).append('\n');
        }
        return signature.append("primary key:").append(String.join(",", table.primaryKeyColumns()))
                .toString();
    }

    /** The letters a tagged ordinal begins with, or nothing where the suffix is all digits. */
    private static String tagOf(String suffix) {
        int letters = 0;
        while (letters < suffix.length() && !isDigit(suffix.charAt(letters))) {
            letters++;
        }
        return suffix.substring(0, letters);
    }

    /** The digits of a suffix as the number they are, or zero where it has none. */
    private static BigInteger numberOf(String suffix) {
        StringBuilder digits = new StringBuilder();
        for (int index = 0; index < suffix.length(); index++) {
            if (isDigit(suffix.charAt(index))) {
                digits.append(suffix.charAt(index));
            }
        }
        return digits.isEmpty() ? BigInteger.ZERO : new BigInteger(digits.toString());
    }

    private static boolean isDigit(char character) {
        return character >= '0' && character <= '9';
    }
}
