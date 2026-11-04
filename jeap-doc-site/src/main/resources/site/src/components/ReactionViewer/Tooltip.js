import React, {useLayoutEffect, useRef} from 'react';
import styles from './styles.module.css';

export default function Tooltip({id, hover}) {
    const ref = useRef(null);
    useLayoutEffect(() => {
        const element = ref.current, parent = element.parentElement;
        const x = hover.x + 14 + element.offsetWidth <= parent.clientWidth ? hover.x + 14 : hover.x - element.offsetWidth - 14;
        const y = hover.y + 14 + element.offsetHeight <= parent.clientHeight ? hover.y + 14 : hover.y - element.offsetHeight - 14;
        element.style.left = Math.max(8, x) + 'px';
        element.style.top = Math.max(8, y) + 'px';
    }, [hover]);
    return <div ref={ref} id={id} role="tooltip" className={styles.tooltip}>{hover.text}</div>;
}
