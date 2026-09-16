/**
 * Builds a table from a `grouped-table` directive: three columns, a first cell that spans the rows of its group,
 * and a last cell that may hold blocks such as a fold. A Markdown table can do neither.
 *
 * ```
 * :::::grouped-table
 * :::column[Version]
 * :::
 * ...
 * ::::group[1.0.0]
 * :::row[Key]
 * <blocks of the last cell>
 * :::
 * ::::
 * :::::
 * ```
 *
 * Like `remark-details`, the elements are built here and take no attribute from the page, and everything in
 * them is escaped Markdown. Anything else inside the directive is left out.
 */
const isContainer = (node, name) => node.type === 'containerDirective' && node.name === name;

/** The label of a container directive, and what follows it. */
const labelled = (node) => {
    const [first, ...rest] = node.children;
    return first?.data?.directiveLabel ? {label: first.children, content: rest} : {label: [], content: node.children};
};

const element = (tagName, children, properties = {}) =>
    ({type: 'groupedTablePart', data: {hName: tagName, hProperties: properties}, children});

const tableOf = (directive) => {
    const headers = directive.children.filter((child) => isContainer(child, 'column'))
        .map((column) => element('th', labelled(column).label));
    const rows = [];
    for (const group of directive.children.filter((child) => isContainer(child, 'group'))) {
        const {label, content} = labelled(group);
        const groupRows = content.filter((child) => isContainer(child, 'row'));
        groupRows.forEach((row, index) => {
            const cells = index === 0 ? [element('td', label, {rowSpan: groupRows.length})] : [];
            const {label: rowLabel, content: cell} = labelled(row);
            cells.push(element('td', rowLabel), element('td', cell));
            rows.push(element('tr', cells));
        });
    }
    return element('table', [element('thead', [element('tr', headers)]), element('tbody', rows)],
        {className: ['groupedTable']});
};

module.exports = function remarkGroupedTable() {
    const transform = (node) => {
        if (!Array.isArray(node.children)) {
            return;
        }
        node.children.forEach(transform);
        node.children = node.children.map((child) => (isContainer(child, 'grouped-table') ? tableOf(child) : child));
    };
    return (root) => transform(root);
};
