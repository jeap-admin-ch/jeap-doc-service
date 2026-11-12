package ch.admin.bit.jeap.doc.domain.architecture;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;

/**
 * Replaces the tables of each configured group with one entry, before {@link ShardFamilies} sees the rest.
 * <p>
 * <b>A group needs the same shape, as a family of partitions does.</b> Of the tables a pattern matches, only
 * those of the most common shape are grouped; the others stay tables of their own and the entry says how
 * many there are. A pattern is a claim about names, and an entry whose columns did not hold for every table
 * it stands for would be wrong about the ones that differ.
 * <p>
 * <b>Grouping happens before collapsing</b>, because a group lists the entities it stands for, and the
 * partitions of an entity are part of it rather than a family of their own.
 */
final class TableGroups {

    /** How many tables of one shape make a group. A single table keeps its own name. */
    private static final int MIN_GROUP = 2;

    private static final Comparator<SchemaTable> BY_NAME =
            Comparator.comparing(SchemaTable::name, String.CASE_INSENSITIVE_ORDER);

    private TableGroups() {
    }

    /**
     * The entries of the configured groups, and every table that is in none of them.
     *
     * @param groups    one per group that grouped anything
     * @param remaining the tables that matched no group, or matched one with another shape
     */
    record Result(List<Group> groups, List<SchemaTable> remaining) {
    }

    /**
     * One entry and the tables it stands for.
     *
     * @param definition the configured group
     * @param entry      the entry, with the foreign keys of its first table until {@link #withSharedKeys} has run
     * @param tables     the tables it stands for, sorted by name
     */
    record Group(SchemaTableGroup definition, SchemaTable entry, List<SchemaTable> tables) {
    }

    /** A table belongs to the first group whose pattern matches its whole name, in the configured order. */
    static Result group(List<SchemaTable> tables, List<SchemaTableGroup> groups) {
        if (groups.isEmpty()) {
            return new Result(List.of(), tables);
        }
        Map<SchemaTableGroup, List<SchemaTable>> matched = new LinkedHashMap<>();
        List<SchemaTable> remaining = new ArrayList<>();
        for (SchemaTable table : tables) {
            SchemaTableGroup group = groups.stream()
                    .filter(candidate -> candidate.matching(table.name()) != null)
                    .findFirst().orElse(null);
            if (group == null) {
                remaining.add(table);
            } else {
                matched.computeIfAbsent(group, ignored -> new ArrayList<>()).add(table);
            }
        }
        List<Group> result = new ArrayList<>();
        matched.forEach((group, members) -> {
            List<SchemaTable> shape = mostCommonShapeOf(members);
            if (shape.size() < MIN_GROUP) {
                remaining.addAll(members);
                return;
            }
            result.add(new Group(group, entryOf(group, shape, members.size() - shape.size()), shape));
            // By identity: a schema of several thousand tables would otherwise compare every column of
            // every table against the whole group.
            Set<SchemaTable> grouped = Collections.newSetFromMap(new IdentityHashMap<>());
            grouped.addAll(shape);
            members.stream().filter(member -> !grouped.contains(member)).forEach(remaining::add);
        });
        return new Result(result, remaining);
    }

    /**
     * The entry with only the foreign keys every one of its tables declares, and a count of the others - see
     * {@link SharedKeys}. The entities of a group can point at different tables, so this matters more for a
     * group than for the partitions of one table.
     *
     * @param entryOf the name of the entry that stands for a table
     */
    static SchemaTable withSharedKeys(Group group, UnaryOperator<String> entryOf) {
        SharedKeys keys = SharedKeys.of(group.tables(), entryOf);
        SchemaTable entry = group.entry();
        GroupedTables tables = entry.group();
        return new SchemaTable(entry.name(), entry.columns(), entry.primaryKeyColumns(), keys.kept(), null,
                new GroupedTables(tables.tables(), tables.members(), tables.differentShape(), keys.partial()));
    }

    /**
     * The tables of the shape most of them have. A tie goes to the shape whose first table sorts first by
     * name, so that the answer does not depend on the order the upstream listed the tables in.
     */
    private static List<SchemaTable> mostCommonShapeOf(List<SchemaTable> tables) {
        Map<String, List<SchemaTable>> byShape = new LinkedHashMap<>();
        for (SchemaTable table : tables) {
            byShape.computeIfAbsent(ShardFamilies.signatureOf(table), ignored -> new ArrayList<>()).add(table);
        }
        return byShape.values().stream()
                .map(shape -> shape.stream().sorted(BY_NAME).toList())
                .min(Comparator.comparingInt((List<SchemaTable> shape) -> -shape.size())
                        .thenComparing(List::getFirst, BY_NAME))
                .orElseThrow();
    }

    /**
     * One entry standing for the group: the shape every grouped table shares, the foreign keys of the first of
     * them by name - narrowed by {@link #withSharedKeys} once every entry is known - and its entities.
     */
    private static SchemaTable entryOf(SchemaTableGroup group, List<SchemaTable> shape, int differentShape) {
        Map<String, List<String>> partitionsByEntity = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Map<String, Integer> tablesByEntity = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (SchemaTable table : shape) {
            Matcher match = group.matching(table.name());
            String entity = match.group(SchemaTableGroup.ENTITY);
            tablesByEntity.merge(entity, 1, Integer::sum);
            String partition = group.hasPartitions() ? match.group(SchemaTableGroup.PARTITION) : null;
            List<String> partitions = partitionsByEntity.computeIfAbsent(entity, ignored -> new ArrayList<>());
            if (partition != null) {
                partitions.add(partition);
            }
        }
        List<GroupedTables.Member> members = new ArrayList<>();
        tablesByEntity.forEach((entity, count) -> {
            List<String> partitions = partitionsByEntity.get(entity).stream()
                    .sorted(ShardFamilies.BY_PARTITION_KEY).toList();
            members.add(new GroupedTables.Member(entity, count,
                    partitions.isEmpty() ? null : partitions.getFirst(),
                    partitions.isEmpty() ? null : partitions.getLast()));
        });
        SchemaTable first = shape.getFirst();
        return new SchemaTable(group.name(), first.columns(), first.primaryKeyColumns(), first.foreignKeys(),
                null, new GroupedTables(shape.size(), members, differentShape, 0));
    }
}
