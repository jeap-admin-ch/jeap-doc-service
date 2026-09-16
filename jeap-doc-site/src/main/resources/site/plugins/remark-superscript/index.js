/**
 * Turns a `:sup[…]` text directive into a `sup` element.
 *
 * Pages stay CommonMark and raw HTML stays escaped: the element is built here, not parsed from the page. It
 * takes no attribute from the directive, so nothing a page writes reaches the element's attributes. The name
 * is `Md.superscript` in `jeap-doc-markdown`, and the two have to agree.
 */
module.exports = function remarkSuperscript() {
    const transform = (node) => {
        if (!Array.isArray(node.children)) {
            return;
        }
        node.children.forEach(transform);
        if (node.type !== 'textDirective' || node.name !== 'sup') {
            return;
        }
        node.data = {hName: 'sup', hProperties: {}};
        node.attributes = {};
    };
    return (root) => transform(root);
};
