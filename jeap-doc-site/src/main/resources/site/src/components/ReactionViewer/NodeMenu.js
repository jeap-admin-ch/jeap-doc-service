import React, {useLayoutEffect, useRef} from 'react';
import styles from './styles.module.css';

export default function NodeMenu({menu, href, linkLabel, canExpand, canCollapse, onChange, onClose}) {
    const ref = useRef(null);
    useLayoutEffect(() => {
        const element = ref.current, parent = element.parentElement;
        element.style.left = Math.max(8, Math.min(menu.x, parent.clientWidth - element.offsetWidth - 8)) + 'px';
        element.style.top = Math.max(8, Math.min(menu.y, parent.clientHeight - element.offsetHeight - 8)) + 'px';
        (element.querySelector('[role="menuitem"]:not(:disabled)') ?? element).focus();
    }, [menu]);
    const onKeyDown = event => {
        if (event.key === 'Escape') { event.preventDefault(); onClose(); }
        if (['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) {
            event.preventDefault();
            const buttons = [...ref.current.querySelectorAll('[role="menuitem"]:not(:disabled)')];
            const current = buttons.indexOf(document.activeElement);
            const index = event.key === 'Home' ? 0 : event.key === 'End' ? buttons.length - 1
                : (current + (event.key === 'ArrowDown' ? 1 : -1) + buttons.length) % buttons.length;
            buttons[index]?.focus();
        }
    };
    return <div ref={ref} role="menu" tabIndex={-1} aria-label={menu.label} className={styles.groupMenu}
        onKeyDown={onKeyDown} onBlur={event => { if (!event.currentTarget.contains(event.relatedTarget)) onClose(false); }}>
        <div className={styles.menuTitle}>{menu.label}</div>
        {href && <a role="menuitem" href={href}>{linkLabel}</a>}
        {!menu.group && !href && <button type="button" role="menuitem" disabled>Documentation unavailable</button>}
        {menu.group && <>
        <button type="button" role="menuitem" disabled={!canCollapse} onClick={() => onChange(false)}>Collapse group</button>
        <button type="button" role="menuitem" disabled={!canExpand} onClick={() => onChange(true)}>Expand group</button>
        </>}
    </div>;
}
