/** Keep each viewer and its static fallback together without accepting HTML or attributes from the page. */
const container = (className, children) => ({
    type: 'reactionViewPart', data: {hName: 'div', hProperties: {className: [className]}}, children,
});

module.exports = function remarkReactionView() {
    const transform = node => {
        if (!Array.isArray(node.children)) return;
        node.children.forEach(transform);
        const children = [];
        for (let index = 0; index < node.children.length; index++) {
            const child = node.children[index];
            if (child.type !== 'code' || child.lang !== 'reaction-view') {
                children.push(child);
                continue;
            }
            const content = [child];
            if (node.children[index + 1]?.type === 'table') {
                content.push(container('jeapReactionTableFallback', [node.children[++index]]));
            }
            children.push(container('jeapReactionView', content));
        }
        node.children = children;
    };
    return transform;
};
