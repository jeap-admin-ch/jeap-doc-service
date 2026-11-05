import React, {useMemo, useState} from 'react';
import styles from './styles.module.css';

const columns = ['Trigger', 'Component', 'Action', 'Median per day'];
const collator = new Intl.Collator('en', {numeric: true, sensitivity: 'base'});
const values = row => [row.trigger ?? '-', row.component, row.answers.join(', ') || '-', row.median];

/** React owns these rows; the general page-table enhancer must leave them untouched. */
export default function ReactionTable({rows}) {
    const [query, setQuery] = useState('');
    const [sort, setSort] = useState({column: -1, direction: 'none'});
    const shown = useMemo(() => {
        const words = query.toLowerCase().split(/\s+/).filter(Boolean);
        const filtered = rows.filter(row => words.every(word => values(row).join(' ').toLowerCase().includes(word)));
        if (sort.direction === 'none') return filtered;
        return filtered.sort((left, right) => {
            const compared = sort.column === 3 ? left.median - right.median
                : collator.compare(String(values(left)[sort.column]), String(values(right)[sort.column]));
            return sort.direction === 'descending' ? -compared : compared;
        });
    }, [rows, query, sort]);
    const sortBy = column => setSort(previous => ({column, direction: previous.column !== column
        || previous.direction === 'none' ? 'ascending' : previous.direction === 'ascending' ? 'descending' : 'none'}));

    return <div className={styles.reactionTable} data-reaction-table="live">
        <div className="jeapTableFilter">
            <span className="jeapTableFilterField"><input type="search" className="jeapTableFilterInput" value={query}
                aria-label="Filter the rows of this table" placeholder="Filter visible reactions"
                onChange={event => setQuery(event.target.value)}
                onKeyDown={event => { if (event.key === 'Escape') setQuery(''); }}/>
            {query && <button type="button" className="jeapTableFilterClear" aria-label="Clear the filter"
                onClick={() => setQuery('')}>×</button>}</span>
            <span className="jeapTableFilterCount" role="status">{shown.length} / {rows.length} sichtbare Reaktionen</span>
        </div>
        <table data-reaction-table="live" aria-label="Eingeblendete Reaktionen">
            <thead><tr>{columns.map((title, column) => <th key={title} scope="col"
                className={column === 3 ? 'jeapTableSortable jeapTableNumeric' : 'jeapTableSortable'}
                aria-sort={sort.column === column ? sort.direction : 'none'}>
                <button type="button" className="jeapTableSort" aria-label={`Sort by ${title}`} onClick={() => sortBy(column)}>
                    {title}<span aria-hidden="true">{sort.column === column && sort.direction !== 'none'
                        ? sort.direction === 'ascending' ? ' ↑' : ' ↓' : ' ↕'}</span>
                </button>
            </th>)}</tr></thead>
            <tbody>{shown.map(row => <tr key={row.id} data-reaction-id={row.id}>
                <td>{row.trigger == null ? '-' : <code>{row.trigger}</code>}</td>
                <td><code>{row.component}</code></td>
                <td>{row.answers.join(', ') || '-'}</td>
                <td className="jeapTableNumeric">{row.median}</td>
            </tr>)}</tbody>
        </table>
        {!rows.length && <p>Keine einzelnen Reaktionen eingeblendet. Klappe eine Gruppe auf, um ihre Reaktionen zu sehen.</p>}
        {rows.length > 0 && !shown.length && <p>Keine eingeblendete Reaktion passt zum Filter.</p>}
    </div>;
}
