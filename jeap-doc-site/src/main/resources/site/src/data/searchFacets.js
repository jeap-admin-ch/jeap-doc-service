/**
 * The two axes a reader narrows a search by, and the one place they are spelled.
 *
 * The results page and the navbar's box both read this, so they cannot disagree about what a value is called
 * or what it means. Every record in the index carries one value of each key - see `SearchRecords` in the
 * service - and a record with no value for a key a query names is excluded by the index, which is why a group
 * everything is selected in is not sent at all.
 */

/** What produced a page. The box in the navbar shows this group and only this one. */
export const SOURCE = {
    key: 'source',
    label: 'Source',
    values: [
        {value: 'generated', label: 'Generated'},
        {value: 'markdown', label: 'Uploaded MD'},
        {value: 'html', label: 'Uploaded HTML'},
    ],
};

/** What a page documents. A library is neither a system nor a component, and is documented like both. */
export const SUBJECT = {
    key: 'subject',
    label: 'Subject',
    values: [
        {value: 'system', label: 'System'},
        {value: 'component', label: 'Component'},
        {value: 'library', label: 'Library'},
    ],
};

export const FACETS = [SOURCE, SUBJECT];

const ALL = (group) => group.values.map((value) => value.value);

/**
 * What is selected, read out of the URL.
 *
 * An absent parameter is everything selected, which is what a search that nobody has narrowed looks like -
 * and it is why the URL of a default search is exactly what it was before there were any filters. A value
 * nobody knows is ignored rather than kept, so an old link cannot select nothing at all.
 */
export function selectionFrom(parameters, groups = FACETS) {
    const selection = {};
    for (const group of groups) {
        const asked = (parameters.get(group.key) ?? '').split(',').filter(Boolean);
        const known = asked.filter((value) => ALL(group).includes(value));
        selection[group.key] = known.length > 0 ? known : ALL(group);
    }
    return selection;
}

/**
 * The filters of one search: the environment, and every group the reader has narrowed.
 *
 * **A group is sent as `{any: [...]}`.** An array is read as "all of these at once", and since a record
 * carries one value per key, a two-value array returns nothing at all - silently. A group everything is
 * selected in is left out, which is identical to sending every value where every record has one, and right
 * where a record has none.
 */
export function filtersOf(selection, environment, groups = FACETS) {
    const filters = {environment: [environment]};
    for (const group of groups) {
        const selected = selection[group.key] ?? ALL(group);
        if (selected.length < group.values.length) {
            filters[group.key] = {any: selected};
        }
    }
    return filters;
}

/**
 * The filters of the search that only counts: the environment, and every group named but empty.
 *
 * **A group Pagefind was never asked about has no counts.** It answers `filters` and `totalFilters` for the
 * keys a query mentions and for no others, so a search that narrows nothing comes back with nothing to put on
 * a chip. Naming a group with an empty list mentions it without narrowing by it - an empty group is ignored
 * rather than matched against - so this answers the same result set as the search beside it, with the counts.
 *
 * **And the counts to use are `filters`, not `totalFilters`.** The second ignores every filter, the
 * environment included, so on a site with four environments it says four times what a reader would see. The
 * first counts within the set this query narrowed to, which is the reader's environment and nothing else.
 */
export function countingFiltersOf(environment, groups = FACETS) {
    const filters = {environment: [environment]};
    for (const group of groups) {
        filters[group.key] = [];
    }
    return filters;
}

/** Writes the selection into the URL parameters, omitting a group the reader has not narrowed. */
export function writeInto(parameters, selection, groups = FACETS) {
    for (const group of groups) {
        const selected = selection[group.key] ?? ALL(group);
        if (selected.length < group.values.length) {
            parameters.set(group.key, selected.join(','));
        } else {
            parameters.delete(group.key);
        }
    }
    return parameters;
}

/**
 * One chip turned on or off.
 *
 * **The last selected chip of a group cannot be turned off.** An empty group is ignored by the index rather
 * than matching nothing, so a page that allowed one would show every result while every chip looked
 * unselected - which reads as a broken control. Resetting is how a reader gets back to everything.
 */
export function toggled(selection, group, value) {
    const selected = selection[group.key] ?? ALL(group);
    if (!selected.includes(value)) {
        return {...selection, [group.key]: ALL(group).filter((each) => selected.includes(each) || each === value)};
    }
    if (selected.length === 1) {
        return selection;
    }
    return {...selection, [group.key]: selected.filter((each) => each !== value)};
}

/** Everything selected again. */
export function everything(groups = FACETS) {
    const selection = {};
    for (const group of groups) {
        selection[group.key] = ALL(group);
    }
    return selection;
}

/** Whether the reader has narrowed anything at all, which is what the reset chip is for. */
export function isNarrowed(selection, groups = FACETS) {
    return groups.some((group) => (selection[group.key] ?? ALL(group)).length < group.values.length);
}
