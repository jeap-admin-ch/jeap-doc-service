import React from 'react';
import clsx from 'clsx';
import styles from './styles.module.css';

/**
 * The chips a reader narrows a search with.
 *
 * Everything is selected to begin with, so the first thing a reader sees is every result - the chips are for
 * taking things away rather than for building a query up. Each one says how many results it would bring, from
 * the counts the index answers **for the query rather than for the narrowed set**: a chip that is off has to
 * say what turning it on would find, not zero.
 *
 * The groups are separated by a gap and by a colour, because "what produced it" and "what it documents" are
 * two questions and a single row of six chips reads as one.
 */
export default function SearchFacets({groups, selection, counts, onToggle, onReset, showReset = true}) {
    return (
        <div className={styles.facets} role="group" aria-label="Narrow the results">{/* NOSONAR a fieldset brings a border and legend the chips do not want */}
            {groups.map((group) => (
                <div key={group.key} className={clsx(styles.group, styles[group.key])}>
                    {group.values.map((value) => {
                        const selected = (selection[group.key] ?? []).includes(value.value);
                        const count = counts?.[group.key]?.[value.value];
                        return (
                            <button
                                key={value.value}
                                type="button"
                                className={clsx(styles.chip, selected && styles.selected)}
                                aria-pressed={selected}
                                onClick={() => onToggle(group, value.value)}>
                                {value.label}
                                {count !== undefined && <span className={styles.count}>{count}</span>}
                            </button>
                        );
                    })}
                </div>
            ))}
            {showReset && (
                <button
                    type="button"
                    className={styles.reset}
                    aria-label="Show everything again"
                    title="Show everything again"
                    onClick={onReset}>
                    ✕
                </button>
            )}
        </div>
    );
}
