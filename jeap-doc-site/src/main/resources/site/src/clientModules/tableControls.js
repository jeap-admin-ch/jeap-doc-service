/**
 * Sorting on every table of a page, a filter above every table with more than FILTER_THRESHOLD rows, and the
 * rest of a long cell behind a chip.
 *
 * Generated and uploaded pages alike: this changes the page in the reader's browser only, and nothing on the
 * page has to ask for it. It moves the nodes a table already has and never builds a cell from a string, so a
 * cell keeps its links, its code and its escaping.
 *
 * A table is left alone when it has no header text, or a merged cell in its body - a grouped table's rows
 * belong together, and sorting them one by one would tear a group apart.
 */

/** How many body rows a table has before it gets a filter. */
const FILTER_THRESHOLD = 15;

/** How many comma-separated items a cell shows before the rest go behind the chip. */
const CELL_THRESHOLD = 3;

/** Marks a cell this module has collapsed, so a route update on the same page does not collapse it twice. */
const COLLAPSED = 'data-jeap-doc-cell-collapsed';

/** Marks a cell the filter opened, so clearing the filter closes exactly those again. */
const OPENED_BY_FILTER = 'data-jeap-doc-opened-by-filter';

/** Marks a table this module has enhanced, so a route update on the same page does not enhance it twice. */
const ENHANCED = 'data-jeap-doc-table-controls';

const COLLATOR = new Intl.Collator('en', {numeric: true, sensitivity: 'base'});

/** Digits, separators and whitespace, with at least one digit. */
const NUMERIC = /^[\d\s'’.,]*\d[\d\s'’.,]*$/;

const SVG = 'http://www.w3.org/2000/svg';

function svg(viewBox, paths, className) {
    const icon = document.createElementNS(SVG, 'svg');
    icon.setAttribute('viewBox', viewBox);
    icon.setAttribute('aria-hidden', 'true');
    icon.setAttribute('focusable', 'false');
    icon.setAttribute('class', className);
    for (const [d, pathClass] of paths) {
        const path = document.createElementNS(SVG, 'path');
        path.setAttribute('d', d);
        if (pathClass) {
            path.setAttribute('class', pathClass);
        }
        icon.append(path);
    }
    return icon;
}

function sortIcon() {
    const arrows = document.createElement('span');
    arrows.className = 'jeapTableSortArrows';
    arrows.append(svg('0 0 8 5', [['M4 0 8 5H0z', null]], 'jeapTableSortUp'),
        svg('0 0 8 5', [['M4 5 0 0h8z', null]], 'jeapTableSortDown'));
    return arrows;
}

function searchIcon() {
    return svg('0 0 16 16', [['M6.5 1a5.5 5.5 0 0 1 4.38 8.82l3.65 3.65-1.06 1.06-3.65-3.65A5.5 5.5 0 1 1 6.5 1Zm0 '
                              + '1.5a4 4 0 1 0 0 8 4 4 0 0 0 0-8Z', null]], 'jeapTableFilterIcon');
}

function comparable(text) {
    return text.replaceAll(/['’]/g, '').trim();
}

function isCandidate(table) {
    if (table.hasAttribute(ENHANCED) || !table.tHead || table.tBodies.length !== 1) {
        return false;
    }
    const header = table.tHead.rows[0];
    if (!header || ![...header.cells].some((cell) => cell.textContent.trim())) {
        return false;
    }
    return !table.tBodies[0].querySelector('[rowspan], [colspan]');
}

function escapeForPattern(word) {
    return word.replaceAll(/[.*+?^${}()|[\]\\]/g, String.raw`\$&`);
}

/**
 * The direction a click on a column header moves to: ascending, then descending, then back to the order the
 * page was written in. A click on another column starts that cycle over.
 */
function nextDirection(sorted, column) {
    if (sorted.column !== column || sorted.direction === 'none') {
        return 'ascending';
    }
    return sorted.direction === 'ascending' ? 'descending' : 'none';
}

/** Wraps every match of the words in a `mark`, on text nodes only. */
function mark(row, words) {
    const pattern = new RegExp(words.map(escapeForPattern).join('|'), 'gi');
    const walker = document.createTreeWalker(row, NodeFilter.SHOW_TEXT);
    const texts = [];
    while (walker.nextNode()) {
        texts.push(walker.currentNode);
    }
    for (const text of texts) {
        const value = text.nodeValue;
        pattern.lastIndex = 0;
        if (!pattern.test(value)) {
            continue;
        }
        pattern.lastIndex = 0;
        const parts = document.createDocumentFragment();
        let from = 0;
        for (const match of value.matchAll(pattern)) {
            parts.append(value.slice(from, match.index));
            const highlight = document.createElement('mark');
            highlight.className = 'jeapTableMark';
            highlight.textContent = match[0];
            parts.append(highlight);
            from = match.index + match[0].length;
        }
        parts.append(value.slice(from));
        text.replaceWith(parts);
    }
}

function unmark(body) {
    for (const highlight of body.querySelectorAll('mark.jeapTableMark')) {
        const parent = highlight.parentNode;
        highlight.replaceWith(highlight.textContent);
        parent.normalize();
    }
}

/**
 * The cell's children as items, split at the commas between them.
 * <p>
 * <b>A separator is a child of the cell that is nothing but a comma</b>, which is what a generated list of
 * links or code spans leaves between two items. A comma inside a longer text node is prose - a description
 * reading "orders, invoices and returns" is one item, not three - so nothing is split there, and a cell of
 * prose is never collapsed however many commas it has.
 * <p>
 * A separator belongs to the item that follows it, so hiding the items after the third hides their commas
 * with them.
 */
function itemsOf(cell) {
    const items = [];
    let current = null;
    for (const node of cell.childNodes) {
        if (node.nodeType === Node.TEXT_NODE && node.nodeValue.trim() === ',') {
            current = [node];
            items.push(current);
            continue;
        }
        if (current === null) {
            current = [];
            items.push(current);
        }
        current.push(node);
    }
    return items;
}

/** Shows or hides what a collapsed cell holds behind its chip. */
function setExpanded(cell, expanded) {
    const rest = cell.querySelector(':scope > .jeapCellRest');
    const more = cell.querySelector(':scope > .jeapCellMore');
    if (!rest || !more) {
        return;
    }
    rest.hidden = !expanded;
    more.setAttribute('aria-expanded', String(expanded));
    more.textContent = expanded ? 'show fewer' : `+${more.dataset.rest} more`;
}

/**
 * The rest of a long cell behind a chip: a cell of counterparts is complete in the page - for the filter and
 * for the search index - and shows its first three.
 */
function collapseCells(table) {
    for (const body of table.tBodies) {
        for (const cell of body.querySelectorAll('td')) {
            if (cell.hasAttribute(COLLAPSED)) {
                continue;
            }
            const items = itemsOf(cell);
            if (items.length <= CELL_THRESHOLD) {
                continue;
            }
            const rest = document.createElement('span');
            rest.className = 'jeapCellRest';
            rest.hidden = true;
            items.slice(CELL_THRESHOLD).forEach((item) => rest.append(...item));
            const more = document.createElement('button');
            more.type = 'button';
            more.className = 'jeapCellMore';
            more.dataset.rest = String(items.length - CELL_THRESHOLD);
            more.setAttribute('aria-expanded', 'false');
            more.textContent = `+${more.dataset.rest} more`;
            more.addEventListener('click', () => setExpanded(cell, rest.hidden));
            cell.append(rest, more);
            cell.setAttribute(COLLAPSED, 'true');
        }
    }
}

/** A cell that hides what the reader filtered for is opened, or they see a row and not the word they typed. */
function openWhatTheFilterMatched(row) {
    for (const rest of row.querySelectorAll('.jeapCellRest[hidden]')) {
        if (!rest.querySelector('mark.jeapTableMark')) {
            continue;
        }
        const cell = rest.closest('td');
        setExpanded(cell, true);
        cell.setAttribute(OPENED_BY_FILTER, 'true');
    }
}

/** And is closed again when the filter no longer needs it open. */
function closeWhatTheFilterOpened(body) {
    for (const cell of body.querySelectorAll(`td[${OPENED_BY_FILTER}]`)) {
        cell.removeAttribute(OPENED_BY_FILTER);
        setExpanded(cell, false);
    }
}

function enhance(table) {
    table.setAttribute(ENHANCED, 'true');
    const body = table.tBodies[0];
    const headers = [...table.tHead.rows[0].cells];
    const written = [...body.rows];
    // Read once: the filter compares against these, not against the page on every keystroke.
    const rowText = new Map(written.map((row) => [row, row.textContent.toLowerCase()]));
    let emptyRow = null;
    let sorted = {column: -1, direction: 'none'};

    const cellText = (row, column) => row.cells[column]?.textContent ?? '';

    function sortBy(column) {
        const next = nextDirection(sorted, column);
        sorted = {column, direction: next};
        headers.forEach((header) => header.removeAttribute('aria-sort'));
        if (next !== 'none') {
            headers[column].setAttribute('aria-sort', next);
        }
        const order = next === 'none' ? written : [...written].sort((a, b) => {
            const compared = COLLATOR.compare(comparable(cellText(a, column)), comparable(cellText(b, column)));
            return next === 'descending' ? -compared : compared;
        });
        body.append(...order);
        if (emptyRow) {
            body.append(emptyRow);
        }
    }

    headers.forEach((header, column) => {
        const title = header.textContent.trim();
        if (!title) {
            return;
        }
        const numeric = written.length > 0 && written.every((row) => NUMERIC.test(cellText(row, column).trim()));
        if (numeric) {
            header.classList.add('jeapTableNumeric');
            written.forEach((row) => row.cells[column]?.classList.add('jeapTableNumeric'));
        }
        const button = document.createElement('button');
        button.type = 'button';
        button.className = 'jeapTableSort';
        button.setAttribute('aria-label', `Sort by ${title}`);
        const label = document.createElement('span');
        label.append(...header.childNodes);
        // For a numeric column the stylesheet puts the arrows in front, so the text lines up with its numbers.
        button.append(label, sortIcon());
        button.addEventListener('click', () => sortBy(column));
        header.classList.add('jeapTableSortable');
        header.append(button);
    });

    if (written.length <= FILTER_THRESHOLD) {
        return;
    }

    const bar = document.createElement('div');
    bar.className = 'jeapTableFilter';
    const input = document.createElement('input');
    input.type = 'search';
    input.className = 'jeapTableFilterInput';
    input.placeholder = `Filter ${written.length} rows`;
    input.setAttribute('aria-label', 'Filter the rows of this table');
    const clear = document.createElement('button');
    clear.type = 'button';
    clear.className = 'jeapTableFilterClear';
    clear.setAttribute('aria-label', 'Clear the filter');
    clear.textContent = '×';
    clear.hidden = true;
    const count = document.createElement('span');
    count.className = 'jeapTableFilterCount';
    count.setAttribute('aria-live', 'polite');
    count.textContent = `${written.length} rows`;
    const field = document.createElement('span');
    field.className = 'jeapTableFilterField';
    field.append(searchIcon(), input, clear);
    bar.append(field, count);
    table.before(bar);

    function filter(query) {
        const words = query.toLowerCase().split(/\s+/).filter(Boolean);
        closeWhatTheFilterOpened(body);
        unmark(body);
        emptyRow?.remove();
        emptyRow = null;
        let shown = 0;
        for (const row of written) {
            const matches = words.every((word) => rowText.get(row).includes(word));
            row.hidden = !matches;
            if (matches) {
                shown++;
                if (words.length > 0) {
                    mark(row, words);
                    openWhatTheFilterMatched(row);
                }
            }
        }
        if (words.length === 0) {
            count.textContent = `${written.length} rows`;
        } else {
            const matched = document.createElement('b');
            matched.textContent = String(shown);
            count.replaceChildren(matched, ` of ${written.length} rows`);
        }
        clear.hidden = query.length === 0;
        if (words.length > 0 && shown === 0) {
            emptyRow = document.createElement('tr');
            emptyRow.className = 'jeapTableEmpty';
            const cell = document.createElement('td');
            cell.colSpan = headers.length;
            cell.textContent = `No row contains “${query.trim()}”.`;
            emptyRow.append(cell);
            body.append(emptyRow);
        }
    }

    function reset() {
        input.value = '';
        filter('');
    }

    input.addEventListener('input', () => filter(input.value));
    input.addEventListener('keydown', (event) => {
        if (event.key === 'Escape') {
            reset();
        }
    });
    clear.addEventListener('click', () => {
        reset();
        input.focus();
    });
}

export default {
    onRouteDidUpdate() {
        try {
            document.querySelectorAll('.theme-doc-markdown table').forEach((table) => {
                if (isCandidate(table)) {
                    enhance(table);
                }
                // After enhance, which reads the rows once for the filter: the chip's label is not row text.
                // Every table, including the grouped one enhance leaves alone - a long cell is long there too.
                collapseCells(table);
            });
        } catch (error) {
            // A defect in this file: the browser suite asserts the console is empty.
            console.error('The tables of this page could not be made sortable.', error);
        }
    },
};
