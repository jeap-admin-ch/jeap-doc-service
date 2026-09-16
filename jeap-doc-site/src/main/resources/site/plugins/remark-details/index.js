/**
 * Turns a `:::details[Summary]` directive into a `details` element with a `summary`, which the theme renders
 * as its collapsible.
 *
 * Pages stay CommonMark and raw HTML stays escaped: the element is built here, not parsed from the page. It
 * takes no attribute from the directive, so nothing a page writes reaches the element's attributes.
 */
module.exports = function remarkDetails() {
    const transform = (node) => {
        if (!Array.isArray(node.children)) {
            return;
        }
        node.children.forEach(transform);
        if (node.type !== 'containerDirective' || node.name !== 'details') {
            return;
        }
        const [first, ...rest] = node.children;
        const labelled = first?.data?.directiveLabel;
        const summary = {
            type: 'paragraph',
            data: {hName: 'summary'},
            children: labelled ? first.children : [{type: 'text', value: 'Details'}],
        };
        node.data = {hName: 'details', hProperties: {}};
        node.attributes = {};
        node.children = [summary, ...(labelled ? rest : node.children)];
    };
    return (root) => transform(root);
};
