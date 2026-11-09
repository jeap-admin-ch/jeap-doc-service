/**
 * Turns `:list[:item[…]:item[…]]` text directives into a `ul` with `li` items.
 *
 * A table cell cannot hold a Markdown list, and raw HTML stays escaped, so the elements are built here. They
 * take no attribute from the directive. The names are `Md.listInCell` in `jeap-doc-markdown`, and the two have
 * to agree. An `item` outside a `list` is left as the text it was written as.
 */
module.exports = function remarkCellList() {
    const transform = (node) => {
        if (!Array.isArray(node.children)) {
            return;
        }
        node.children.forEach(transform);
        if (node.type !== 'textDirective' || node.name !== 'list') {
            return;
        }
        node.data = {hName: 'ul', hProperties: {}};
        node.attributes = {};
        node.children
            .filter((child) => child.type === 'textDirective' && child.name === 'item')
            .forEach((item) => {
                item.data = {hName: 'li', hProperties: {}};
                item.attributes = {};
            });
    };
    return (root) => transform(root);
};
