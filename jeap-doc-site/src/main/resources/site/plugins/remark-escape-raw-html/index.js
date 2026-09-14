/**
 * Shows raw HTML in a page as text instead of passing it through to the reader.
 *
 * Pages are read as CommonMark (`markdown.format: 'md'`), and Docusaurus then hands raw HTML to rehype-raw -
 * so a `<script>` in an uploaded page would run on the site's origin. Turning the `html` nodes into text
 * before that happens keeps the tag visible and inert. Uploaded documentation is not trusted with markup, and
 * the generator writes none: nothing on this site needs a raw tag.
 */

/** Nodes whose children are inline, where a paragraph of its own would not be allowed. */
const PHRASING_PARENTS = new Set(['paragraph', 'heading', 'emphasis', 'strong', 'delete', 'link', 'tableCell']);

module.exports = function remarkEscapeRawHtml() {
    const escape = (parent) => {
        if (!Array.isArray(parent.children)) {
            return;
        }
        parent.children = parent.children.map((child) => {
            if (child.type !== 'html') {
                escape(child);
                return child;
            }
            const text = {type: 'text', value: child.value};
            return PHRASING_PARENTS.has(parent.type) ? text : {type: 'paragraph', children: [text]};
        });
    };
    return (root) => escape(root);
};
